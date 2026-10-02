package fr.xephi.authme.command.executable.authme;

import ch.jalu.datasourcecolumns.data.DataSourceValue;
import fr.xephi.authme.command.ExecutableCommand;
import fr.xephi.authme.data.VerificationCodeManager;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.EmailConfirmedEvent;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.process.login.AsynchronousLogin;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.service.AccountMigrationService;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.EmailPasswordService;
import fr.xephi.authme.service.PendingEmailChangeCache;
import fr.xephi.authme.service.SessionService;
import fr.xephi.authme.service.ValidationService;
import fr.xephi.authme.service.bungeecord.BungeeSender;
import fr.xephi.authme.service.bungeecord.MessageType;
import fr.xephi.authme.service.velocity.VMessageType;
import fr.xephi.authme.service.velocity.VelocitySender;
import fr.xephi.authme.util.Utils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Admin command to manage the email address bound to a player's account
 * (view, set or delete it), or to manage the accounts bound to an email address.
 */
public class EmailChangeCommand implements ExecutableCommand {

    @Inject
    private DataSource dataSource;

    @Inject
    private CommonService commonService;

    @Inject
    private PlayerCache playerCache;

    @Inject
    private BukkitService bukkitService;

    @Inject
    private Management management;

    @Inject
    private ValidationService validationService;

    @Inject
    private AccountMigrationService accountMigrationService;

    @Inject
    private EmailPasswordService emailPasswordService;

    @Inject
    private PendingEmailChangeCache pendingEmailChangeCache;

    @Inject
    private SessionService sessionService;

    @Inject
    private VerificationCodeManager codeManager;

    @Inject
    private AsynchronousLogin asynchronousLogin;

    @Inject
    private BungeeSender bungeeSender;

    @Inject
    private VelocitySender velocitySender;

    EmailChangeCommand() {
    }

    @Override
    public void executeCommand(CommandSender sender, List<String> arguments) {
        String subcommand = arguments.isEmpty() ? "" : arguments.get(0).toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "view":
                viewEmail(sender, arguments);
                break;
            case "set":
                setEmail(sender, arguments);
                break;
            case "delete":
                deleteEmail(sender, arguments);
                break;
            case "accounts":
                listAccounts(sender, arguments);
                break;
            case "deleteaccount":
                deleteAccount(sender, arguments);
                break;
            default:
                commonService.send(sender, MessageKey.USAGE_EMAILCHANGE);
                break;
        }
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.USAGE_EMAILCHANGE;
    }

    /**
     * Shows the email address bound to the given player's account.
     *
     * @param sender the command sender
     * @param arguments the command arguments
     */
    private void viewEmail(final CommandSender sender, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(sender, MessageKey.USAGE_EMAILCHANGE);
            return;
        }
        final String playerName = arguments.get(1);

        bukkitService.runTaskOptionallyAsync(() -> {
            DataSourceValue<String> email = dataSource.getEmail(playerName);
            if (!email.rowExists()) {
                commonService.send(sender, MessageKey.UNKNOWN_USER);
            } else if (Utils.isEmailEmpty(email.getValue())) {
                commonService.send(sender, MessageKey.ADMIN_EMAIL_SHOW_EMPTY, playerName);
            } else {
                commonService.send(sender, MessageKey.ADMIN_EMAIL_SHOW, playerName, email.getValue());
            }
        });
    }

    /**
     * Sets the email address bound to the given player's account. The account is considered
     * migrated to the current schema version after an administrator has set the email. An
     * online player is notified in real time and released from the migration process if needed.
     *
     * @param sender the command sender
     * @param arguments the command arguments
     */
    private void setEmail(final CommandSender sender, List<String> arguments) {
        if (arguments.size() < 3) {
            commonService.send(sender, MessageKey.USAGE_EMAILCHANGE);
            return;
        }
        final String playerName = arguments.get(1);
        final String newEmail = arguments.get(2);

        if (!validationService.validateEmail(newEmail)) {
            commonService.send(sender, MessageKey.INVALID_EMAIL);
            return;
        }

        bukkitService.runTaskOptionallyAsync(() -> {
            PlayerAuth auth = dataSource.getAuth(playerName);
            if (auth == null) {
                commonService.send(sender, MessageKey.UNKNOWN_USER);
                return;
            } else if (!validationService.isEmailFreeForRegistration(newEmail, sender)) {
                commonService.send(sender, MessageKey.EMAIL_ALREADY_USED_ERROR);
                return;
            }

            // 管理员设置的邮箱视为完成 v2 迁移：绑定邮箱并推进 schema 版本
            auth.setEmail(newEmail);
            auth.setSchemaVersion(AccountMigrationService.TARGET_SCHEMA_VERSION);
            if (!dataSource.updateEmail(auth) || !dataSource.updateSchemaVersion(auth)) {
                commonService.send(sender, MessageKey.ERROR);
                return;
            }

            // 密码跟随邮箱：该邮箱已有其他账号绑定时，采用其现有密码并同步给同邮箱账号
            boolean passwordAdopted = false;
            HashedPassword emailPassword = emailPasswordService.findPasswordByEmail(newEmail);
            if (emailPassword != null) {
                auth.setPassword(emailPassword);
                if (dataSource.updatePassword(auth)) {
                    emailPasswordService.syncPasswordToEmail(newEmail, emailPassword, auth.getNickname());
                    passwordAdopted = true;
                }
            }

            if (playerCache.getAuth(playerName) != null) {
                playerCache.updatePlayer(auth);
            }

            Player target = bukkitService.getPlayerExact(playerName);
            if (target != null && target.isOnline()) {
                commonService.send(target, MessageKey.ADMIN_EMAIL_SET_NOTIFY, newEmail);
                if (passwordAdopted) {
                    commonService.send(target, MessageKey.EMAIL_PASSWORD_ADOPTED);
                }
                // 通知其他插件：邮箱绑定已被管理员确认（数据整合插件据此向网站后端同步）
                bukkitService.callEvent(new EmailConfirmedEvent(target, newEmail));
                if (accountMigrationService.isInMigrationLimbo(target)) {
                    // 玩家正卡在迁移引导中：作废其待确认的邮箱变更并立即放行登录
                    codeManager.unverify(target.getName().toLowerCase(Locale.ROOT));
                    pendingEmailChangeCache.remove(target.getName());
                    asynchronousLogin.performLogin(target, auth);
                }
            }
            commonService.send(sender, MessageKey.ADMIN_EMAIL_SET_SUCCESS, playerName, newEmail);
        });
    }

    /**
     * Removes the email address bound to the given player's account, reverting it to a v1
     * account. An online, authenticated player is sent back to the unauthenticated state
     * and is guided through the email migration immediately, just like when a player
     * without a bound email logs in.
     *
     * @param sender the command sender
     * @param arguments the command arguments
     */
    private void deleteEmail(final CommandSender sender, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(sender, MessageKey.USAGE_EMAILCHANGE);
            return;
        }
        final String playerName = arguments.get(1);

        bukkitService.runTaskOptionallyAsync(() -> {
            PlayerAuth auth = dataSource.getAuth(playerName);
            if (auth == null) {
                commonService.send(sender, MessageKey.UNKNOWN_USER);
                return;
            }

            // 删除邮箱并将账号回退为 v1（无邮箱绑定），下次登录将重新进入迁移引导
            auth.setEmail("");
            auth.setSchemaVersion(null);
            if (!dataSource.updateEmail(auth) || !dataSource.updateSchemaVersion(auth)) {
                commonService.send(sender, MessageKey.ERROR);
                return;
            }

            Player target = bukkitService.getPlayerExact(playerName);
            if (target != null && target.isOnline() && playerCache.isAuthenticated(playerName)) {
                // 在线已登录玩家：立即退回未登录状态，并触发与“未绑定邮箱玩家登录”一致的迁移引导
                String name = target.getName().toLowerCase(Locale.ROOT);
                playerCache.removePlayer(name);
                codeManager.unverify(name);
                pendingEmailChangeCache.remove(name);
                dataSource.setUnlogged(name);
                sessionService.revokeSession(name);
                bungeeSender.sendAuthMeBungeecordMessage(target, MessageType.LOGOUT);
                velocitySender.sendAuthMeVelocityMessage(target, VMessageType.LOGOUT);
                commonService.send(target, MessageKey.ADMIN_EMAIL_DELETE_NOTIFY);
                accountMigrationService.handlePendingMigration(target, auth);
            } else if (playerCache.getAuth(playerName) != null) {
                playerCache.updatePlayer(auth);
            }
            commonService.send(sender, MessageKey.ADMIN_EMAIL_DELETE_SUCCESS, playerName);
        });
    }

    /**
     * Lists all accounts bound to the given email address.
     *
     * @param sender the command sender
     * @param arguments the command arguments
     */
    private void listAccounts(final CommandSender sender, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(sender, MessageKey.USAGE_EMAILCHANGE);
            return;
        }
        final String email = arguments.get(1).toLowerCase(Locale.ROOT);

        bukkitService.runTaskOptionallyAsync(() -> {
            List<String> accounts = dataSource.getAllAuthsByEmail(email);
            if (accounts.isEmpty()) {
                commonService.send(sender, MessageKey.ADMIN_EMAIL_ACCOUNTS_EMPTY, email);
                return;
            }

            commonService.send(sender, MessageKey.ADMIN_EMAIL_ACCOUNTS_HEADER,
                email, String.valueOf(accounts.size()));
            for (String account : accounts) {
                commonService.send(sender, MessageKey.ADMIN_EMAIL_ACCOUNTS_ENTRY, account);
            }
        });
    }

    /**
     * Unregisters the given account, e.g. one found with the accounts subcommand.
     *
     * @param sender the command sender
     * @param arguments the command arguments
     */
    private void deleteAccount(CommandSender sender, List<String> arguments) {
        if (arguments.size() < 2) {
            commonService.send(sender, MessageKey.USAGE_EMAILCHANGE);
            return;
        }
        String playerName = arguments.get(1);

        if (!dataSource.isAuthAvailable(playerName)) {
            commonService.send(sender, MessageKey.UNKNOWN_USER);
            return;
        }

        Player target = bukkitService.getPlayerExact(playerName);
        management.performUnregisterByAdmin(sender, playerName, target);
    }
}
