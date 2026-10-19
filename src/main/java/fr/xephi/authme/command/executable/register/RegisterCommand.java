package fr.xephi.authme.command.executable.register;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.captcha.RegistrationCaptchaManager;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.process.register.executors.PasswordRegisterParams;
import fr.xephi.authme.process.register.executors.RegistrationMethod;
import fr.xephi.authme.process.register.executors.TwoFactorRegisterParams;
import fr.xephi.authme.security.HashAlgorithm;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.service.ValidationService.ValidationResult;
import fr.xephi.authme.settings.properties.SecuritySettings;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Command for /register.
 *
 * <p>Registration is done with a password directly:
 * {@code /register <password> <confirmPassword>}. An email address is optional
 * and can be bound later with {@code /email add}.</p>
 */
public class RegisterCommand extends PlayerCommand {

    @Inject
    private Management management;

    @Inject
    private CommonService commonService;

    @Inject
    private ValidationService validationService;

    @Inject
    private RegistrationCaptchaManager registrationCaptchaManager;

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

        // Registration: /register <password> <confirmPassword>
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
}
