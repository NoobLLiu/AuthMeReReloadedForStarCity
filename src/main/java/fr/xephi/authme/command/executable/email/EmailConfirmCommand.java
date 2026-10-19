package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.VerificationCodeManager;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.EmailConfirmedEvent;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.EmailPasswordService;
import fr.xephi.authme.service.PendingEmailChangeCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Command for confirming a pending email change with a verification code.
 *
 * <p>Completes the two-phase email binding flow started by
 * {@code /email add} or {@code /email change}. The player supplies the code
 * received by email; if it matches the cached pending change, the new email
 * is persisted to the database.</p>
 *
 * <p>For authenticated players this command also accepts the sensitive-operation
 * verification code (unregister, change password), matching the instruction in the
 * verification email.</p>
 */
public class EmailConfirmCommand extends PlayerCommand {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(EmailConfirmCommand.class);

    @Inject
    private CommonService commonService;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    @Inject
    private VerificationCodeManager codeManager;

    @Inject
    private EmailPasswordService emailPasswordService;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private DataSource dataSource;

    @Inject
    private BukkitService bukkitService;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);

        if (!playerCache.isAuthenticated(playerName)) {
            // Database operations are performed asynchronously, as in the other async processes
            bukkitService.runTaskAsynchronously(() -> {
                if (dataSource.isAuthAvailable(playerName)) {
                    commonService.send(player, MessageKey.LOGIN_MESSAGE);
                } else {
                    commonService.send(player, MessageKey.REGISTER_MESSAGE);
                }
            });
            return;
        }

        PendingEmailChangeCache.PendingEmailChange pending = pendingEmailChangeCache.get(playerName);
        if (pending == null) {
            // Sensitive-operation verification code (e.g. unregister, change password):
            // the verification email instructs "/email confirm <code>", so confirm it here too
            if (codeManager.isCodeRequired(playerName)) {
                if (codeManager.checkCode(playerName, arguments.get(0))) {
                    commonService.send(player, MessageKey.VERIFICATION_CODE_VERIFIED);
                } else {
                    commonService.send(player, MessageKey.INCORRECT_VERIFICATION_CODE);
                }
            } else {
                commonService.send(player, MessageKey.EMAIL_NO_PENDING_CHANGE);
            }
            return;
        }

        String code = arguments.get(0);
        if (!pending.getCode().equals(code)) {
            commonService.send(player, MessageKey.EMAIL_CONFIRM_WRONG_CODE);
            return;
        }

        // Phase 2: code matches — persist the new email.
        // Database operations are performed asynchronously, as in the other async processes
        bukkitService.runTaskAsynchronously(() -> {
            PlayerAuth auth = playerCache.getAuth(playerName);
            auth.setEmail(pending.getNewEmail());
            // The password follows the email: if the new email already has a password
            // (other accounts are bound to it), it is adopted by this account
            HashedPassword emailPassword = emailPasswordService.findPasswordByEmail(pending.getNewEmail());
            boolean saved = dataSource.updateEmail(auth);
            if (emailPassword != null) {
                auth.setPassword(emailPassword);
                saved = saved && dataSource.updatePassword(auth);
            }
            if (saved) {
                // Record the UUID together with the email binding, so the identity switch
                // feature can hand out the account's own UUID instead of a regenerated one
                auth.setUuid(player.getUniqueId());
                if (!dataSource.updateUuid(auth)) {
                    logger.warning("Could not save UUID for player '" + player.getName() + "'");
                }
                playerCache.updatePlayer(auth);
                pendingEmailChangeCache.remove(playerName);
                commonService.send(player, MessageKey.EMAIL_CONFIRM_SUCCESS);
                if (emailPassword != null) {
                    emailPasswordService.syncPasswordToEmail(pending.getNewEmail(), emailPassword, playerName);
                    commonService.send(player, MessageKey.EMAIL_PASSWORD_ADOPTED);
                }
                // 通知其他插件：邮箱绑定确认完成（数据整合插件据此向网站后端同步）
                Bukkit.getPluginManager().callEvent(new EmailConfirmedEvent(player, pending.getNewEmail()));
            } else {
                logger.warning("Could not save email for player '" + player + "'");
                commonService.send(player, MessageKey.ERROR);
            }
        });
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_EMAIL_CONFIRM;
    }
}
