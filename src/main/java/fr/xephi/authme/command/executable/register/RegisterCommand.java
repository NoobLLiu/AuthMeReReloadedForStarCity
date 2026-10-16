package fr.xephi.authme.command.executable.register;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.captcha.RegistrationCaptchaManager;
import fr.xephi.authme.events.EmailConfirmedEvent;
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
import fr.xephi.authme.service.PendingEmailChangeCache;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.service.ValidationService.ValidationResult;
import fr.xephi.authme.settings.properties.SecuritySettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Command for /register.
 *
 * <p>Registration is done with a password directly:
 * {@code /register <password> <confirmPassword>}. An email address is optional
 * and can be bound later with {@code /email add}.</p>
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
    private ValidationService validationService;

    @Inject
    private RegistrationCaptchaManager registrationCaptchaManager;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    @Inject
    private AccountMigrationService accountMigrationService;

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

        // Normal registration: /register <password> <confirmPassword>
        if (arguments.size() < 2) {
            commonService.send(player, MessageKey.USAGE_REGISTER);
            return;
        }
        final String password = arguments.get(0);
        if (!password.equals(arguments.get(1))) {
            commonService.send(player, MessageKey.PASSWORD_MATCH_ERROR);
            return;
        }

        // Fail fast on an invalid password
        ValidationResult passwordValidation = validationService.validatePassword(password, player.getName());
        if (passwordValidation.hasError()) {
            commonService.send(player, passwordValidation.getMessageKey(), passwordValidation.getArgs());
            return;
        }

        management.performRegister(RegistrationMethod.PASSWORD_REGISTRATION,
            PasswordRegisterParams.of(player, password, null));
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
     * Last step of a v1 account migration with a fresh email address: sets the new
     * password, which completes the migration and resumes the intercepted login.
     *
     * @param player the player whose migration password is set
     * @param arguments the provided arguments ({@code <password> <confirmPassword>})
     */
    private void handleMigrationPasswordPhase(Player player, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(player, MessageKey.USAGE_REGISTER);
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
