package fr.xephi.authme.command.executable.identity;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.identity.IdentityBedrockFormService;
import fr.xephi.authme.identity.IdentityMenuService;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Opens the identity menu (/lg) showing the current account, its bound email address and
 * the other accounts registered under the same email, which can be switched to.
 * Bedrock players connected through Geyser + Floodgate receive a native Bedrock form
 * with the same content; Java players receive the chest inventory.
 * The argument {@code sync} manually records the UUID the player is currently connected
 * with into their own account.
 */
public class IdentityMenuCommand extends PlayerCommand {

    @Inject
    private IdentityMenuService identityMenuService;
    @Inject
    private IdentityBedrockFormService identityBedrockFormService;
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
        if (!arguments.isEmpty() && "sync".equalsIgnoreCase(arguments.get(0))) {
            identitySwitchManager.syncOwnUuid(player);
            return;
        }
        if (IdentitySwitchManager.isBedrockPlayer(player)) {
            identityBedrockFormService.open(player);
            return;
        }
        identityMenuService.open(player);
    }
}
