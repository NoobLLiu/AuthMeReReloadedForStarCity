package fr.xephi.authme.command.executable.unregister;

import fr.xephi.authme.command.PlayerCommand;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.service.CommonService;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.List;

/**
 * Command for a player to unregister himself. Self-unregistration is
 * disabled: players are directed to contact an administrator, who can
 * use {@code /authme unregister <player>} instead.
 */
public class UnregisterCommand extends PlayerCommand {

    @Inject
    private CommonService commonService;

    @Override
    public void runCommand(Player player, List<String> arguments) {
        commonService.send(player, MessageKey.UNREGISTER_DISABLED);
    }

    @Override
    public MessageKey getArgumentsMismatchMessage() {
        return MessageKey.UNREGISTER_DISABLED;
    }

    @Override
    protected String getAlternativeCommand() {
        return "/authme unregister <player>";
    }
}
