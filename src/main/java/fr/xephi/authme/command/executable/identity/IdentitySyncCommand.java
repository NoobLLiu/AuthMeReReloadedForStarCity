package fr.xephi.authme.command.executable.identity;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * The {@code /lg sync} command: manually records the UUID the player is currently connected
 * with into their own account.
 */
public class IdentitySyncCommand extends PlayerCommand {

    @Inject
    private IdentitySwitchManager identitySwitchManager;
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
        identitySwitchManager.syncOwnUuid(player);
    }
}
