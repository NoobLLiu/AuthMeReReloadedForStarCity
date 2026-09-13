package com.authme.geyser;

import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * Manages pending identity switch data shared between AuthMe and the Geyser Extension.
 * <p>
 * AuthMe writes pending switch files to {@code plugins/AuthMe/geyser-pending-switches/}
 * keyed by the Bedrock player's XUID. This class reads and consumes those files when a
 * Bedrock player reconnects through Geyser.
 */
public class PendingSwitchStore {

    private static final String SWITCH_DIR_NAME = "geyser-pending-switches";
    private static final String LINKED_MODE_MARKER = "geyser-linked-mode.enabled";
    private static final long EXPIRY_MILLIS = 5 * 60 * 1000L; // 5 minutes (slightly longer than AuthMe's 3-min window)

    private final Path switchDir;
    private final ExtensionLogger logger;

    public PendingSwitchStore(Path authMeDir, ExtensionLogger logger) {
        this.switchDir = authMeDir.resolve(SWITCH_DIR_NAME);
        this.logger = logger;
    }

    /**
     * Returns whether AuthMe's Floodgate linked-identity hook is active. In linked mode
     * the hook serves pending switches through Floodgate's linked-player query during
     * the handshake, so this extension must leave the session untouched.
     *
     * @return true if the linked-mode marker file exists in AuthMe's data folder
     */
    public boolean isLinkedModeEnabled() {
        return Files.exists(switchDir.getParent().resolve(LINKED_MODE_MARKER));
    }

    /**
     * Reads and consumes the pending switch for the given XUID. Returns null if no
     * pending switch exists or it has expired.
     *
     * @param xuid the Bedrock player's Xbox User ID
     * @return the pending switch data, or null
     */
    public PendingSwitchData getAndConsume(String xuid) {
        if (xuid == null || xuid.isEmpty()) {
            return null;
        }
        Path file = switchDir.resolve(xuid + ".properties");
        if (!Files.exists(file)) {
            return null;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            logger.error("Failed to read pending switch file for XUID '" + xuid + "': " + e.getMessage());
            return null;
        }

        // Delete the file after reading (one-shot consumption)
        try {
            Files.delete(file);
        } catch (IOException e) {
            logger.warning("Could not delete pending switch file '" + file + "': " + e.getMessage());
        }

        try {
            String targetName = props.getProperty("targetName");
            String targetUuidStr = props.getProperty("targetUuid");
            String ip = props.getProperty("ip");
            long timestamp = Long.parseLong(props.getProperty("timestamp", "0"));

            if (targetName == null || targetName.isEmpty() || targetUuidStr == null || targetUuidStr.isEmpty()) {
                logger.warning("Pending switch file for XUID '" + xuid + "' has missing target data");
                return null;
            }

            // Check expiry
            if (System.currentTimeMillis() - timestamp > EXPIRY_MILLIS) {
                logger.info("Pending switch for XUID '" + xuid + "' has expired");
                return null;
            }

            UUID targetUuid = UUID.fromString(targetUuidStr);
            return new PendingSwitchData(targetName, targetUuid, ip, timestamp);
        } catch (Exception e) {
            logger.error("Failed to parse pending switch for XUID '" + xuid + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Cleans up expired pending switch files. Called periodically.
     */
    public void cleanupExpired() {
        if (!Files.isDirectory(switchDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(switchDir, "*.properties")) {
            long now = System.currentTimeMillis();
            for (Path file : stream) {
                try {
                    Properties props = new Properties();
                    try (InputStream in = Files.newInputStream(file)) {
                        props.load(in);
                    }
                    long timestamp = Long.parseLong(props.getProperty("timestamp", "0"));
                    if (now - timestamp > EXPIRY_MILLIS) {
                        Files.delete(file);
                        logger.debug("Cleaned up expired pending switch file: " + file.getFileName());
                    }
                } catch (Exception e) {
                    // Skip files we can't parse
                }
            }
        } catch (IOException e) {
            logger.warning("Error during pending switch cleanup: " + e.getMessage());
        }
    }

    /**
     * Simple data class holding the pending switch information.
     */
    public static class PendingSwitchData {
        private final String targetName;
        private final UUID targetUuid;
        private final String ip;
        private final long timestamp;

        public PendingSwitchData(String targetName, UUID targetUuid, String ip, long timestamp) {
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.ip = ip;
            this.timestamp = timestamp;
        }

        public String getTargetName() {
            return targetName;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getIp() {
            return ip;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
