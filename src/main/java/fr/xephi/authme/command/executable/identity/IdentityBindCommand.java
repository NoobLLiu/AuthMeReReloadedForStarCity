package fr.xephi.authme.command.executable.identity;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.identity.IdentityBindManager;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * The {@code /lg bind <account> <password>} command: binds the given account to the
 * player's account after verifying the account's password.
 */
public class IdentityBindCommand extends PlayerCommand {

    @Inject
    private IdentityBindManager identityBindManager;
    @Inject
    private PlayerCache playerCache;
    @Inject
    private Messages messages;

    @Override
    protected void runCommand(Player player, List<String> arguments) {
        if (!playerCache.isAuthenticated(player.getName())) {
            messages.send(player, MessageKey.NOT_LOGGED_IN);
            return;
        }
        identityBindManager.bindAccount(player, arguments.get(0), arguments.get(1));
    }
}
