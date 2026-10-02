package fr.xephi.authme.command.executable.authme;

import ch.jalu.datasourcecolumns.data.DataSourceValue;
import fr.xephi.authme.command.ExecutableCommand;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.Management;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.CommonService;
import fr.xephi.authme.service.ValidationService;
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
     * Sets the email address bound to the given player's account.
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

            auth.setEmail(newEmail);
            if (!dataSource.updateEmail(auth)) {
                commonService.send(sender, MessageKey.ERROR);
                return;
            }

            if (playerCache.getAuth(playerName) != null) {
                playerCache.updatePlayer(auth);
            }
            commonService.send(sender, MessageKey.ADMIN_EMAIL_SET_SUCCESS, playerName, newEmail);
        });
    }

    /**
     * Removes the email address bound to the given player's account.
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

            auth.setEmail("");
            if (!dataSource.updateEmail(auth)) {
                commonService.send(sender, MessageKey.ERROR);
                return;
            }

            if (playerCache.getAuth(playerName) != null) {
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
