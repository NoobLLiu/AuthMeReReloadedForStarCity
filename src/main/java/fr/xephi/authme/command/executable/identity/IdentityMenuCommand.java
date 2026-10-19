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
 * Opens the account switch panel (/lg): shows the current account, a button to bind a new
 * account (verified by that account's password) and all accounts bound to the current one,
 * which can be switched to. An email address is no longer required.
 * Bedrock players connected through Geyser + Floodgate receive a native Bedrock form
 * with the same content; Java players receive the chest inventory.
 * Related subcommands: {@code /lg bind}, {@code /lg unbind}, {@code /lg sync}.
 */
public class IdentityMenuCommand extends PlayerCommand {

    @Inject
    private IdentityMenuService identityMenuService;
    @Inject
    private IdentityBedrockFormService identityBedrockFormService;
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
        if (IdentitySwitchManager.isBedrockPlayer(player)) {
            identityBedrockFormService.open(player);
            return;
        }
        identityMenuService.open(player);
    }
}
