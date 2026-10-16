package fr.xephi.authme.listener;

import fr.xephi.authme.identity.IdentityBindManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import javax.inject.Inject;

/**
 * Captures the chat input of the bind-new-account flow for Java players: after a player
 * clicked the bind button in the identity menu (/lg), the next chat message is cancelled
 * (never broadcast) and interpreted as {@code <account> <password>} — or {@code cancel} to
 * abort the input.
 */
public class IdentityBindChatListener implements Listener {

    private final IdentityBindManager bindManager;

    @Inject
    IdentityBindChatListener(IdentityBindManager bindManager) {
        this.bindManager = bindManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        if (!bindManager.isChatInputActive(event.getPlayer().getName())) {
            return;
        }
        // The message contains the account name and password: never let it through
        event.setCancelled(true);
        bindManager.handleChatInput(event.getPlayer(), event.getMessage());
    }
}
