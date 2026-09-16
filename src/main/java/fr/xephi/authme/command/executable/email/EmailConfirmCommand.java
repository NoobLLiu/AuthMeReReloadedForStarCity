package fr.xephi.authme.command.executable.email;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.EmailConfirmedEvent;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.process.login.AsynchronousLogin;
import fr.xephi.authme.process.register.executors.EmailAdoptRegisterParams;
import fr.xephi.authme.process.register.executors.RegistrationMethod;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.service.AccountMigrationService;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.EmailPasswordService;
import fr.xephi.authme.service.PendingEmailChangeCache;
import fr.xephi.authme.service.PendingRegistrationCache;
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
 * <p>For unauthenticated players this command additionally handles two flows:
 * the email binding of a v1 account pending migration, and the email
 * confirmation step of the two-phase v2 registration.</p>
 */
public class EmailConfirmCommand extends PlayerCommand {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(EmailConfirmCommand.class);

    @Inject
    private CommonService commonService;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    @Inject
    private PendingRegistrationCache pendingRegistrationCache;

    @Inject
    private AccountMigrationService accountMigrationService;

    @Inject
    private EmailPasswordService emailPasswordService;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private DataSource dataSource;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private Management management;

    @Inject
    private AsynchronousLogin asynchronousLogin;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        String playerName = player.getName().toLowerCase(Locale.ROOT);

        if (!playerCache.isAuthenticated(playerName)) {
            // v1 account pending migration: confirming the code completes the email
            // binding and resumes the intercepted login
            if (accountMigrationService.isAwaitingEmailBinding(player)) {
                processMigrationConfirmation(player, playerName, arguments.get(0));
                return;
            }
            // Two-phase v2 registration: confirm the email address with the code
            if (pendingRegistrationCache.has(playerName)) {
                processRegistrationConfirmation(player, playerName, arguments.get(0));
                return;
            }
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
            commonService.send(player, MessageKey.EMAIL_NO_PENDING_CHANGE);
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

    /**
     * Handles the verification code of a v1 account pending migration. If the email
     * already has a password (other accounts are bound to it), the password is adopted
     * and the migration completes immediately; otherwise the player is asked to set
     * a new password to finish the migration. The pending email stays in the cache
     * until the password is set.
     *
     * @param player the player to migrate
     * @param playerName the lowercased player name
     * @param code the verification code supplied by the player
     */
    private void processMigrationConfirmation(Player player, String playerName, String code) {
        PendingEmailChangeCache.PendingEmailChange pending = pendingEmailChangeCache.get(playerName);
        if (pending == null) {
            commonService.send(player, MessageKey.EMAIL_NO_PENDING_CHANGE);
            return;
        }
        if (!pending.getCode().equals(code)) {
            commonService.send(player, MessageKey.EMAIL_CONFIRM_WRONG_CODE);
            return;
        }

        // Database operations are performed asynchronously, as in the other async processes
        bukkitService.runTaskAsynchronously(() -> {
            PlayerAuth auth = accountMigrationService.handleMigrationEmailConfirmed(player, pending.getNewEmail());
            if (auth != null) {
                // The email already had a password: the migration is complete
                pendingEmailChangeCache.remove(playerName);
                // 通知其他插件：邮箱绑定确认完成（数据整合插件据此向网站后端同步）
                Bukkit.getPluginManager().callEvent(new EmailConfirmedEvent(player, pending.getNewEmail()));
                asynchronousLogin.performLogin(player, auth);
            }
        });
        // Otherwise the player must still set a new password; the pending email is
        // kept in the cache and the guidance message has already been sent
    }

    /**
     * Handles the verification code of the two-phase v2 registration. If the confirmed
     * email address is already bound to other accounts, its password is adopted: the
     * account is registered with it right away and the player is logged in. Otherwise
     * the email address is marked as confirmed and the player may set a password.
     *
     * @param player the player registering
     * @param playerName the lowercased player name
     * @param code the verification code supplied by the player
     */
    private void processRegistrationConfirmation(Player player, String playerName, String code) {
        PendingRegistrationCache.PendingRegistration pending = pendingRegistrationCache.get(playerName);
        if (pending.isVerified()) {
            commonService.send(player, MessageKey.REGISTER_USAGE_PASSWORD);
            return;
        }
        if (!pending.getCode().equals(code)) {
            commonService.send(player, MessageKey.EMAIL_CONFIRM_WRONG_CODE);
            return;
        }

        // The password follows the email: if the email is already in use and has a
        // password, the new account adopts it instead of asking for a new one.
        // Database operations are performed asynchronously, as in the other async processes
        bukkitService.runTaskAsynchronously(() -> {
            HashedPassword emailPassword = emailPasswordService.findPasswordByEmail(pending.getEmail());
            if (emailPassword != null) {
                // The pending registration is removed once the adoption has been persisted
                // (in EmailAdoptRegisterExecutor), so a failed save keeps the confirmed state
                management.performRegister(RegistrationMethod.EMAIL_ADOPT_REGISTRATION,
                    EmailAdoptRegisterParams.of(player, pending.getEmail(), emailPassword));
                return;
            }

            pending.setVerified(true);
            commonService.send(player, MessageKey.REGISTER_EMAIL_CONFIRMED);
        });
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_EMAIL_CONFIRM;
    }
}
