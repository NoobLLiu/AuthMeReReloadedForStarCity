package fr.xephi.authme.mail;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends emails by invoking the Tencent Agent Mail CLI ({@code agently-cli}).
 *
 * <p>This sender does not use SMTP; instead it shells out to the
 * {@code agently-cli} command-line tool (installed via
 * {@code npm install -g @tencent-qqmail/agently-cli} and authorized with
 * {@code agently-cli auth login}). The sender address is the
 * {@code @agent.qq.com} mailbox bound to the authorized account.</p>
 *
 * <p>The CLI uses a two-phase confirmation flow: the first call returns a
 * {@code confirmation_token} in its JSON output; a second call with
 * {@code --confirmation-token <token>} completes the send. This class
 * automates both phases in a single {@link #sendMail} invocation.</p>
 */
public class AgentMailSender implements MailSender {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(AgentMailSender.class);

    /** Pattern to extract confirmation_token from JSON output. */
    private static final Pattern TOKEN_PATTERN =
        Pattern.compile("\"confirmation_token\"\\s*:\\s*\"([^\"]+)\"");
    /** Pattern to detect success in the second-phase output. */
    private static final Pattern QUEUED_PATTERN =
        Pattern.compile("\"queued\"\\s*:\\s*true");
    /** Pattern to detect "ok": true in JSON output. */
    private static final Pattern OK_PATTERN =
        Pattern.compile("\"ok\"\\s*:\\s*true");
    /** Pattern to detect the CLI's token-storage lock error. */
    private static final Pattern LOCK_ERROR_PATTERN =
        Pattern.compile("credential_lock_unavailable|token storage lock", Pattern.CASE_INSENSITIVE);
    /** Total attempts made when the CLI's token-storage lock is unavailable. */
    private static final int MAX_LOCK_ATTEMPTS = 3;
    /** Delay between lock-retry attempts in milliseconds. */
    private static final long LOCK_RETRY_DELAY_MS = 2000L;

    /**
     * Guards all CLI invocations. The CLI holds an exclusive lock on its token
     * storage, so concurrent sends (e.g. two players requesting a code at the
     * same time) would fight over that lock and fail with
     * {@code credential_lock_unavailable}. Serializing CLI usage avoids this.
     */
    private static final Object CLI_LOCK = new Object();

    @Inject
    private Settings settings;

    @Override
    public boolean hasAllInformation() {
        // Agent Mail relies on an externally authorized CLI; there is no in-config
        // account or password to validate. We assume the admin has installed and
        // authorized agently-cli when selecting this sender.
        return true;
    }

    @Override
    public boolean sendMail(String recipient, String subject, String htmlContent, File imageFile) {
        String cliPath = settings.getProperty(EmailSettings.AGENT_MAIL_CLI_PATH);
        int timeoutSeconds = settings.getProperty(EmailSettings.AGENT_MAIL_TIMEOUT_SECONDS);

        // On Windows, Java's ProcessBuilder does not resolve .cmd/.bat extensions
        // automatically. If the cliPath is a bare name (no path separator, no extension),
        // try appending ".cmd" to locate the npm-generated wrapper.
        cliPath = resolveWindowsCommand(cliPath);
        // Prefer the native CLI binary over the node run.js launcher: run.js starts the
        // real CLI as a child process, which destroyForcibly() cannot kill on timeout,
        // leaving an orphaned CLI process that keeps holding the CLI's token lock.
        cliPath = preferNativeBinary(cliPath);
        logger.info("AgentMailSender: using cliPath=" + cliPath + " for recipient=" + recipient);

        // Write the body to a temp file and use --body-file. This is the CLI's
        // recommended way to send complex HTML (containing <, >, quotes, etc.)
        // and avoids argument-quoting issues across both phases.
        // The CLI requires --body-file to be a RELATIVE path inside the current
        // working directory subtree, so the file must be created under the JVM's
        // cwd (a file in the system temp dir would be rejected as non-relative).
        File bodyFile = null;
        try {
            String body = htmlContent;
            if (imageFile != null && body != null) {
                body = body.replace("<image />",
                    "[Password image is attached to this email.]");
            }
            File cwdDir = new File(".").getAbsoluteFile();
            bodyFile = File.createTempFile("authme-agentmail-", ".html", cwdDir);
            bodyFile.deleteOnExit();
            java.nio.file.Files.write(bodyFile.toPath(),
                (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));

            // Build the base command (without confirmation token)
            List<String> baseCommand = buildSendCommand(cliPath, recipient, subject, bodyFile, imageFile);

            synchronized (CLI_LOCK) {
                // Phase 1: send request to get confirmation token
                String phase1Output = runCliCommandWithLockRetry(baseCommand, timeoutSeconds);
                if (phase1Output == null) {
                    return false;
                }
                logger.info("AgentMailSender phase1 output: " + phase1Output.trim());

                String token = extractToken(phase1Output);
                if (token == null) {
                    // No token returned — only treat as success if there's no confirmation_required
                    // and no error. The Phase 1 response always has "ok": true even when it
                    // requires confirmation, so "ok": true alone is NOT a success signal.
                    if (!phase1Output.contains("\"confirmation_required\"")
                            && !phase1Output.contains("\"error\"")
                            && OK_PATTERN.matcher(phase1Output).find()) {
                        logger.info("agently-cli sent mail to " + recipient + " without confirmation");
                        return true;
                    }
                    logger.warning("agently-cli did not return a confirmation token. Output: " + phase1Output);
                    if (looksLikeAuthError(phase1Output)) {
                        logger.warning("Agent Mail CLI may not be authorized. Run 'agently-cli auth login' "
                            + "and complete the WeChat OAuth flow on the server host.");
                    }
                    return false;
                }
                logger.info("AgentMailSender got confirmation token: " + token);

                // Phase 2: confirm and send with the token
                List<String> confirmCommand = new ArrayList<>(baseCommand);
                confirmCommand.add("--confirmation-token");
                confirmCommand.add(token);

                String phase2Output = runCliCommandWithLockRetry(confirmCommand, timeoutSeconds);
                if (phase2Output == null) {
                    return false;
                }
                logger.info("AgentMailSender phase2 output: " + phase2Output.trim());

                if (QUEUED_PATTERN.matcher(phase2Output).find()) {
                    logger.info("AgentMailSender: mail queued successfully to " + recipient);
                    return true;
                }
                // "ok": true with no confirmation_required and no error = success
                if (OK_PATTERN.matcher(phase2Output).find()
                        && !phase2Output.contains("\"confirmation_required\"")
                        && !phase2Output.contains("\"error\"")) {
                    logger.info("AgentMailSender: mail sent successfully to " + recipient);
                    return true;
                }

                logger.warning("agently-cli confirmation phase did not complete. Output: " + phase2Output);
                return false;
            }
        } catch (Exception e) {
            logger.logException("Failed to send via agently-cli:", e);
            return false;
        } finally {
            if (bodyFile != null) {
                try { bodyFile.delete(); } catch (Exception ignored) { }
            }
        }
    }

    private List<String> buildSendCommand(String cliPath, String recipient, String subject,
                                          File bodyFile, File imageFile) {
        List<String> command = new ArrayList<>();
        // cliPath may be "node \"C:\\path\\run.js\"" (resolved from .cmd wrapper on Windows)
        // or a simple "agently-cli" / "C:\\...\\agently-cli.cmd"
        if (cliPath.startsWith("node ")) {
            command.add("node");
            // Extract the path between quotes
            int firstQuote = cliPath.indexOf('"');
            int lastQuote = cliPath.lastIndexOf('"');
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                command.add(cliPath.substring(firstQuote + 1, lastQuote));
            } else {
                command.add(cliPath.substring(5).trim());
            }
        } else {
            command.add(cliPath);
        }
        command.add("message");
        command.add("+send");
        command.add("--to");
        command.add(recipient);
        command.add("--subject");
        command.add(subject == null ? "" : subject);
        // Use --body-file (relative path) for HTML content to avoid quoting issues
        // with <, >, & characters across the two CLI phases.
        // The CLI requires the path to be RELATIVE to the current working directory.
        command.add("--body-file");
        command.add(makeRelativePath(bodyFile));
        if (imageFile != null) {
            command.add("--attachment");
            // Attachment paths must also be relative to the current directory
            command.add(makeRelativePath(imageFile));
        }
        return command;
    }

    /**
     * Converts an absolute file path into a path relative to the JVM's current
     * working directory, using the platform-specific separator. The agently-cli
     * requires {@code --body-file} to be relative to the cwd; using an
     * absolute path (e.g. inside a temp directory) causes it to fail with
     * "must be a relative path" and exit code 1.
     *
     * @param file the file to relativize
     * @return relative path string, or absolute path as fallback
     */
    private static String makeRelativePath(File file) {
        try {
            String cwd = new File(".").getAbsoluteFile().getCanonicalPath();
            String abs = file.getAbsoluteFile().getCanonicalPath();
            if (abs.startsWith(cwd + File.separator)) {
                return abs.substring(cwd.length() + 1);
            }
        } catch (Exception ignored) {
            // fall through to absolute path
        }
        return file.getAbsolutePath();
    }

    /**
     * Runs the CLI command, retrying briefly when the CLI's token-storage lock
     * is unavailable (e.g. an orphaned CLI process from a previous killed run,
     * or a short-lived stale lock). Returns the last output on persistent
     * lock errors so callers can log the CLI's own error details.
     */
    private String runCliCommandWithLockRetry(List<String> command, int timeoutSeconds) {
        String output = runCliCommand(command, timeoutSeconds);
        for (int attempt = 2; output != null && isLockError(output) && attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            logger.warning("agently-cli token storage lock unavailable, retrying (attempt "
                + attempt + "/" + MAX_LOCK_ATTEMPTS + ") in " + LOCK_RETRY_DELAY_MS + "ms ...");
            try {
                Thread.sleep(LOCK_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            output = runCliCommand(command, timeoutSeconds);
        }
        if (isLockError(output)) {
            logger.warning("agently-cli could not acquire its token storage lock. AuthMe serializes its "
                + "own sends, so the lock is held by another live process on this host: check for "
                + "leftover agently-cli/node processes (e.g. left behind by a previous timeout or "
                + "server shutdown) and end them, then retry. Note: a leftover lock file itself is "
                + "harmless; the CLI locks via the OS and the lock is released automatically when "
                + "its holder exits, so deleting files cannot help.");
        }
        return output;
    }

    private static boolean isLockError(String output) {
        return output != null && LOCK_ERROR_PATTERN.matcher(output).find();
    }

    /**
     * Runs the CLI command and returns its combined stdout/stderr output.
     * Returns {@code null} only if the process could not be started or was
     * killed after exceeding the timeout; the output is returned even when the
     * CLI exits with a non-zero code so callers can inspect the error details.
     */
    private String runCliCommand(List<String> command, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = null;
        StringBuilder output = new StringBuilder();
        Thread readerThread = null;
        try {
            process = pb.start();
            final Process proc = process;
            // Drain the output on a separate thread so waitFor(timeout) below
            // is actually reached even when the CLI hangs without closing stdout.
            readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // stream closed when the process is destroyed
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                logger.warning("agently-cli timed out after " + timeoutSeconds + "s");
                return null;
            }
            // Give the reader thread a moment to drain the tail of the stream.
            readerThread.join(TimeUnit.SECONDS.toMillis(2));
            String result;
            synchronized (output) {
                result = output.toString();
            }
            if (process.exitValue() != 0) {
                logger.warning("agently-cli exited with code " + process.exitValue()
                    + ". Output: " + result);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            logger.logException("Interrupted while waiting for agently-cli:", e);
            return null;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            logger.logException("Failed to invoke agently-cli:", e);
            logger.warning("Ensure agently-cli is installed (npm install -g @tencent-qqmail/agently-cli) "
                + "and present in the server process PATH, or set Email.agentMailCliPath to an absolute path.");
            return null;
        }
    }

    private static String extractToken(String output) {
        Matcher m = TOKEN_PATTERN.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /**
     * On Windows, Java's ProcessBuilder does not automatically resolve .cmd/.bat
     * extensions for bare command names (unlike cmd.exe or PowerShell). npm global
     * installs create {@code agently-cli.cmd} wrappers, but those wrappers can
     * mangle arguments containing HTML characters ({@code <}, {@code >}) which are
     * common in email bodies. Instead, resolve the underlying JS entry point and
     * invoke it directly with {@code node}.
     *
     * @param cliPath the configured CLI path
     * @return resolved path usable by ProcessBuilder
     */
    private static String resolveWindowsCommand(String cliPath) {
        if (cliPath == null || cliPath.isEmpty()) {
            return cliPath;
        }
        // Already has an extension or is an absolute/relative path — leave as-is.
        if (cliPath.contains(".") || cliPath.contains("\\") || cliPath.contains("/")) {
            return cliPath;
        }
        // Try .cmd extension (npm global install creates .cmd wrappers on Windows).
        String cmdPath = cliPath + ".cmd";
        File cmdFile = new File(cmdPath);
        if (cmdFile.exists()) {
            // Resolve the JS entry point from the .cmd wrapper to avoid argument
            // mangling by cmd.exe when HTML body contains < and > characters.
            String jsPath = resolveJsEntryFromCmd(cmdFile);
            if (jsPath != null) {
                return jsPath;
            }
            return cmdPath;
        }
        // Fall back to checking common npm global paths.
        String npmGlobal = System.getenv("APPDATA");
        if (npmGlobal != null && !npmGlobal.isEmpty()) {
            String npmCmd = npmGlobal + "\\npm\\" + cliPath + ".cmd";
            if (new File(npmCmd).exists()) {
                String jsPath = resolveJsEntryFromCmd(new File(npmCmd));
                if (jsPath != null) {
                    return jsPath;
                }
                return npmCmd;
            }
        }
        // Last resort: return with .cmd appended and let ProcessBuilder try.
        return cmdPath;
    }

    /**
     * Parses a npm-generated .cmd wrapper to find the JS entry point and returns
     * a command string in the form {@code node <path-to-run.js>}.
     *
     * @param cmdFile the .cmd wrapper file
     * @return {@code "node <path>"} or null if the JS entry point cannot be resolved
     */
    private static String resolveJsEntryFromCmd(File cmdFile) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(cmdFile.toPath());
            for (String line : lines) {
                // npm .cmd wrappers typically end with a line like:
                //   ... "%_prog%"  "%dp0%\node_modules\...\run.js" %*
                int jsIdx = line.indexOf("run.js");
                if (jsIdx > 0) {
                    // Extract the path containing run.js
                    int quoteStart = line.lastIndexOf('"', jsIdx);
                    int dp0Idx = line.indexOf("%dp0%", quoteStart);
                    if (quoteStart >= 0 && dp0Idx >= 0) {
                        // %dp0% is the directory of the .cmd file
                        String jsRelative = line.substring(dp0Idx + 5, jsIdx + 6); // "node_modules\...\run.js"
                        String jsAbsPath = cmdFile.getParentFile().getAbsolutePath() + "\\" + jsRelative.replace("/", "\\");
                        File jsFile = new File(jsAbsPath);
                        if (jsFile.exists()) {
                            return "node \"" + jsAbsPath + "\"";
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore — fall back to using the .cmd file directly
        }
        return null;
    }

    /**
     * Replaces a command that routes through the {@code run.js} launcher with the
     * platform-native CLI binary when it can be located. {@code run.js} starts the
     * real CLI binary as a child process (via {@code execFileSync}); when a send is
     * killed on timeout, {@code destroyForcibly()} only terminates the direct child
     * (node), leaving an orphaned {@code agently-cli} process behind — still holding
     * the CLI's token storage lock and breaking every later send with
     * {@code credential_lock_unavailable}. Launching the native binary directly
     * removes that extra process layer so the kill reaches the lock holder.
     *
     * @param cliPath the resolved CLI command
     * @return the native binary path if available, otherwise the original command
     */
    private static String preferNativeBinary(String cliPath) {
        String runJsPath = null;
        if (cliPath.startsWith("node ")) {
            String rest = cliPath.substring("node ".length()).trim();
            if (rest.length() >= 2 && rest.charAt(0) == '"' && rest.charAt(rest.length() - 1) == '"') {
                rest = rest.substring(1, rest.length() - 1);
            }
            runJsPath = rest;
        } else if (cliPath.endsWith("run.js")) {
            runJsPath = cliPath;
        }
        if (runJsPath == null) {
            return cliPath;
        }
        String nativeBin = resolveNativeBinaryFromRunJs(runJsPath);
        return nativeBin != null ? nativeBin : cliPath;
    }

    /**
     * Locates the platform-specific CLI binary installed as an optional dependency
     * next to the {@code agently-cli} package, e.g.
     * {@code node_modules/@tencent-qqmail/agently-cli-win32-x64/bin/agently-cli.exe}.
     *
     * @param runJsPath path of the {@code run.js} launcher script
     * @return the native binary path, or null when it cannot be located
     */
    private static String resolveNativeBinaryFromRunJs(String runJsPath) {
        File scriptsDir = new File(runJsPath).getAbsoluteFile().getParentFile();
        File mainPkgDir = scriptsDir == null ? null : scriptsDir.getParentFile();
        File scopeDir = mainPkgDir == null ? null : mainPkgDir.getParentFile();
        if (scopeDir == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String binaryName = "agently-cli" + (windows ? ".exe" : "");
        String jvmArch = System.getProperty("os.arch", "").toLowerCase();
        String[] npmArches = jvmArch.contains("aarch64") || jvmArch.contains("arm64")
            ? new String[] {"arm64", "x64"} : new String[] {"x64", "arm64"};
        File[] searchRoots = {scopeDir,
            new File(mainPkgDir, "node_modules" + File.separator + "@tencent-qqmail")};
        for (File searchRoot : searchRoots) {
            for (String npmArch : npmArches) {
                File binary = new File(searchRoot, "agently-cli-" + npmArch
                    + File.separator + "bin" + File.separator + binaryName);
                if (binary.isFile()) {
                    return binary.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static boolean looksLikeAuthError(String output) {
        if (output == null) {
            return false;
        }
        Pattern p = Pattern.compile("auth|unauthorized|not logged in|login|invalid_grant", Pattern.CASE_INSENSITIVE);
        return p.matcher(output).find();
    }
}
