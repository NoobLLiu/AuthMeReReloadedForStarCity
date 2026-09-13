package fr.xephi.authme.listener;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.geyser.SwitchedPlatformTracker;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.identity.PendingSwitch;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.service.BukkitService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Verifies the identity of a player who reconnects after an identity switch was initiated.
 * Consumes the PendingSwitch and grants auto-login if the player joined with the target
 * identity. For Bedrock players (where the rewrite is ignored by Floodgate's Player
 * creation), the switch is restored so it remains available for future attempts.
 */
public class IdentitySwitchJoinListener implements Listener {

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(IdentitySwitchJoinListener.class);
    private final IdentitySwitchManager identitySwitchManager;
    private final Messages messages;
    private final BukkitService bukkitService;
    private final SwitchedPlatformTracker platformTracker;

    @Inject
    IdentitySwitchJoinListener(IdentitySwitchManager identitySwitchManager, Messages messages,
                               BukkitService bukkitService, SwitchedPlatformTracker platformTracker) {
        this.identitySwitchManager = identitySwitchManager;
        this.messages = messages;
        this.bukkitService = bukkitService;
        this.platformTracker = platformTracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay to let all PreLogin processing finish
        bukkitService.runTaskLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String nameLower = player.getName().toLowerCase(Locale.ROOT);
            String ip = player.getAddress() == null || player.getAddress().getAddress() == null
                ? null
                : player.getAddress().getAddress().getHostAddress();

            // Check for a pending switch that targeted this name
            PendingSwitch consumed = identitySwitchManager.consumePendingSwitchByTarget(nameLower);
            if (consumed != null) {
                // The player joined as the target identity: the switch succeeded
                identitySwitchManager.markAutoLogin(nameLower, ip);
                platformTracker.register(player.getUniqueId(), consumed.isBedrockSource());
                logger.info(String.format("Identity switch completed: player joined as '%s'", player.getName()));
                return;
            }

            // Check for a pending switch initiated by this player
            PendingSwitch pending = identitySwitchManager.getPendingSwitch(nameLower);
            if (pending == null) {
                return;
            }

            // The player joined but NOT as the target identity (typical for Bedrock players
            // where Floodgate ignores the Paper profile rewrite). Keep the switch pending so
            // the player can retry by reconnecting until the switch window expires.
            if (!nameLower.equals(pending.getTargetName().toLowerCase(Locale.ROOT))) {
                logger.info(String.format("Identity switch: '%s' joined but expected '%s' "
                    + "(identity not rewritten); switch kept pending",
                    player.getName(), pending.getTargetRealName()));
                messages.send(player, MessageKey.IDENTITY_SWITCH_NOT_APPLIED);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        platformTracker.unregister(event.getPlayer().getUniqueId());
    }
}
