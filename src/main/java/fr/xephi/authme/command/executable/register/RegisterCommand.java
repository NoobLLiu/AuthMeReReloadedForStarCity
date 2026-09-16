package fr.xephi.authme.command.executable.register;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.captcha.RegistrationCaptchaManager;
import fr.xephi.authme.events.EmailConfirmedEvent;
import fr.xephi.authme.mail.EmailService;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.process.login.AsynchronousLogin;
import fr.xephi.authme.process.register.executors.PasswordRegisterParams;
import fr.xephi.authme.process.register.executors.RegistrationMethod;
import fr.xephi.authme.process.register.executors.TwoFactorRegisterParams;
import fr.xephi.authme.security.HashAlgorithm;
import fr.xephi.authme.service.AccountMigrationService;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.EmailPasswordService;
import fr.xephi.authme.service.PendingEmailChangeCache;
import fr.xephi.authme.service.PendingRegistrationCache;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.service.ValidationService.ValidationResult;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.util.RandomStringUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Command for /register.
 *
 * <p>v2 registration is a two-phase flow: the player first binds an email address
 * with {@code /register <email>} and confirms the verification code with
 * {@code /email confirm <code>}. Once the email address is confirmed, the password
 * is set with {@code /register <password> <confirmPassword>} — unless the email
 * address is already bound to other accounts, in which case its existing password
 * is adopted automatically (the password follows the email).</p>
 *
 * <p>This command also completes the last step of a v1 account migration: a player
 * who confirmed a fresh email address sets the new password with it.</p>
 */
public class RegisterCommand extends PlayerCommand {

    @Inject
    private Management management;

    @Inject
    private CommonService commonService;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private EmailService emailService;

    @Inject
    private ValidationService validationService;

    @Inject
    private RegistrationCaptchaManager registrationCaptchaManager;

    @Inject
    private PendingRegistrationCache pendingRegistrationCache;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    @Inject
    private AccountMigrationService accountMigrationService;

    @Inject
    private EmailPasswordService emailPasswordService;

    @Inject
    private AsynchronousLogin asynchronousLogin;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        if (commonService.getProperty(SecuritySettings.PASSWORD_HASH) == HashAlgorithm.TWO_FACTOR) {
            // for two factor auth we don't need to check the usage
            management.performRegister(RegistrationMethod.TWO_FACTOR_REGISTRATION,
                TwoFactorRegisterParams.of(player));
            return;
        }

        if (!isCaptchaFulfilled(player)) {
            return; // isCaptchaFulfilled handles informing the player on failure
        }

        // v1 account migration: the player confirmed a fresh email address and must now
        // set the new password that completes the migration (an email that already had
        // a password was adopted directly and never reaches this branch)
        if (accountMigrationService.isAwaitingPasswordSet(player)) {
            handleMigrationPasswordPhase(player, arguments);
            return;
        }

        if (accountMigrationService.isAwaitingEmailBinding(player)) {
            commonService.send(player, MessageKey.EMAIL_MIGRATION_REQUIRED);
            return;
        }

        if (arguments.isEmpty()) {
            commonService.send(player, MessageKey.USAGE_REGISTER);
            return;
        }

        PendingRegistrationCache.PendingRegistration pending =
            pendingRegistrationCache.get(player.getName());
        if (pending == null) {
            // Phase 1: bind an email address, a verification code is sent to it
            handleEmailPhase(player, arguments);
        } else if (!pending.isVerified()) {
            // Re-entering an email address replaces the pending one and re-sends the code
            if (arguments.size() == 1 && validationService.validateEmail(arguments.get(0))) {
                handleEmailPhase(player, arguments);
            } else {
                commonService.send(player, MessageKey.REGISTER_VERIFICATION_REQUIRED, pending.getEmail());
            }
        } else {
            // Phase 2: set the password of the confirmed email address
            handlePasswordPhase(player, arguments);
        }
    }

    @Override
    protected String getAlternativeCommand() {
        return "/authme register <playername> <password>";
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_REGISTER;
    }

    private boolean isCaptchaFulfilled(Player player) {
        if (registrationCaptchaManager.isCaptchaRequired(player.getName())) {
            String code = registrationCaptchaManager.getCaptchaCodeOrGenerateNew(player.getName());
            commonService.send(player, MessageKey.CAPTCHA_FOR_REGISTRATION_REQUIRED, code);
            return false;
        }
        return true;
    }

    /**
     * Phase 1 of the registration: validates the given email address and sends a
     * verification code to it. The registration continues once the player confirms
     * the code with {@code /email confirm <code>}. An email address already bound to
     * other accounts is allowed (the same email may be bound by multiple accounts);
     * in that case the player is informed that the email's existing password will
     * be adopted and no new password will be requested.
     *
     * @param player the player to register
     * @param arguments the provided arguments
     */
    private void handleEmailPhase(Player player, List<String> arguments) {
        final String email = arguments.get(0);
        if (!validationService.validateEmail(email)) {
            commonService.send(player, MessageKey.INVALID_EMAIL);
            return;
        }

        final String playerName = player.getName().toLowerCase(Locale.ROOT);
        // Database and mail operations are performed asynchronously, as in the other async processes
        bukkitService.runTaskAsynchronously(() -> {
            if (!emailService.hasAllInformation()) {
                commonService.send(player, MessageKey.INCOMPLETE_EMAIL_SETTINGS);
            } else {
                // The password follows the email: if the email is already bound to other
                // accounts and has a password, it will be adopted and no new one is needed
                boolean passwordReused = emailPasswordService.findPasswordByEmail(email) != null;

                String code = RandomStringUtils.generateNum(6);
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy'-'MM'-'dd'-' HH:mm:ss");
                String time = dateFormat.format(new Date(System.currentTimeMillis()));
                if (emailService.sendVerificationMail(player.getName(), email, code, time)) {
                    pendingRegistrationCache.put(playerName, email, code);
                    commonService.send(player, MessageKey.EMAIL_VERIFICATION_SENT);
                    if (passwordReused) {
                        commonService.send(player, MessageKey.REGISTER_EMAIL_IN_USE_HINT);
                    }
                } else {
                    commonService.send(player, MessageKey.EMAIL_SEND_FAILURE);
                }
            }
        });
    }

    /**
     * Phase 2 of the registration: sets the password of the confirmed email address
     * and creates the account.
     *
     * @param player the player to register
     * @param arguments the provided arguments
     */
    private void handlePasswordPhase(Player player, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(player, MessageKey.REGISTER_USAGE_PASSWORD);
            return;
        }
        final String password = arguments.get(0);
        if (!password.equals(arguments.get(1))) {
            commonService.send(player, MessageKey.PASSWORD_MATCH_ERROR);
            return;
        }

        // Fail fast on an invalid password so the confirmed email does not need to be verified again
        ValidationResult passwordValidation = validationService.validatePassword(password, player.getName());
        if (passwordValidation.hasError()) {
            commonService.send(player, passwordValidation.getMessageKey(), passwordValidation.getArgs());
            return;
        }

        PendingRegistrationCache.PendingRegistration pending =
            pendingRegistrationCache.get(player.getName());
        if (pending == null) {
            commonService.send(player, MessageKey.REGISTER_USAGE_EMAIL);
            return;
        }
        management.performRegister(RegistrationMethod.PASSWORD_REGISTRATION,
            PasswordRegisterParams.of(player, password, pending.getEmail()));
    }

    /**
     * Last step of a v1 account migration with a fresh email address: sets the new
     * password, which completes the migration and resumes the intercepted login.
     *
     * @param player the player whose migration password is set
     * @param arguments the provided arguments ({@code <password> <confirmPassword>})
     */
    private void handleMigrationPasswordPhase(Player player, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(player, MessageKey.REGISTER_USAGE_PASSWORD);
            return;
        }
        final String password = arguments.get(0);
        if (!password.equals(arguments.get(1))) {
            commonService.send(player, MessageKey.PASSWORD_MATCH_ERROR);
            return;
        }

        // Fail fast on an invalid password so the pending email is not consumed
        ValidationResult passwordValidation = validationService.validatePassword(password, player.getName());
        if (passwordValidation.hasError()) {
            commonService.send(player, passwordValidation.getMessageKey(), passwordValidation.getArgs());
            return;
        }

        String playerName = player.getName().toLowerCase(Locale.ROOT);
        PendingEmailChangeCache.PendingEmailChange pending = pendingEmailChangeCache.get(playerName);
        if (pending == null) {
            // The pending email expired: restart the binding from the beginning
            commonService.send(player, MessageKey.EMAIL_MIGRATION_REQUIRED);
            return;
        }

        // Database operations are performed asynchronously, as in the other async processes
        bukkitService.runTaskAsynchronously(() -> {
            PlayerAuth auth = accountMigrationService.completePasswordMigration(
                player, pending.getNewEmail(), password);
            if (auth != null) {
                pendingEmailChangeCache.remove(playerName);
                // 通知其他插件：邮箱绑定确认完成（数据整合插件据此向网站后端同步）
                Bukkit.getPluginManager().callEvent(new EmailConfirmedEvent(player, pending.getNewEmail()));
                asynchronousLogin.performLogin(player, auth);
            }
        });
    }
}
