package fr.xephi.authme.listener;

import fr.xephi.authme.identity.IdentityBedrockFormService;
import fr.xephi.authme.identity.IdentityBindManager;
import fr.xephi.authme.identity.IdentityMenuHolder;
import fr.xephi.authme.identity.IdentityMenuService;
import fr.xephi.authme.identity.IdentitySwitchManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import javax.inject.Inject;
import java.util.List;

/**
 * Handles interaction with the identity menu (/lg): cancels all inventory interaction while
 * the menu is open, initiates the identity switch when an account is clicked (left click),
 * removes the binding when an account is right-clicked and starts the bind-new-account flow
 * when its button is clicked.
 */
public class IdentityMenuClickListener implements Listener {

    private final IdentityMenuService identityMenuService;
    private final IdentitySwitchManager identitySwitchManager;
    private final IdentityBindManager identityBindManager;
    private final IdentityBedrockFormService identityBedrockFormService;

    @Inject
    IdentityMenuClickListener(IdentityMenuService identityMenuService,
                              IdentitySwitchManager identitySwitchManager,
                              IdentityBindManager identityBindManager,
                              IdentityBedrockFormService identityBedrockFormService) {
        this.identityMenuService = identityMenuService;
        this.identitySwitchManager = identitySwitchManager;
        this.identityBindManager = identityBindManager;
        this.identityBedrockFormService = identityBedrockFormService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof IdentityMenuHolder)) {
            return;
        }
        // Block all clicks (including in the player's own inventory) while the menu is open
        event.setCancelled(true);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof IdentityMenuHolder)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        IdentityMenuHolder holder = (IdentityMenuHolder) topInventory.getHolder();
        int slot = event.getSlot();

        if (IdentityMenuService.getAccountSlots().contains(slot)) {
            int index = holder.getPage() * countAccountsPerPage() + positionInAccountSlots(slot);
            List<IdentityMenuHolder.AccountEntry> accounts = holder.getAccounts();
            if (index >= 0 && index < accounts.size()) {
                if (event.isRightClick()) {
                    identityBindManager.unbindAccount(player, accounts.get(index).getRealName());
                } else {
                    identitySwitchManager.initiateSwitch(player, accounts.get(index).getRealName());
                }
            }
        } else if (slot == IdentityMenuService.getBindAddSlot()) {
            if (IdentitySwitchManager.isBedrockPlayer(player)) {
                // Bedrock players get a native input form instead of the chat capture flow
                identityBedrockFormService.openBindForm(player);
            } else {
                identityBindManager.beginChatInput(player);
            }
        } else if (slot == 45) {
            identityMenuService.openPage(player, holder.getEmail(), holder.getAccounts(),
                holder.getPage() - 1);
        } else if (slot == 53) {
            identityMenuService.openPage(player, holder.getEmail(), holder.getAccounts(),
                holder.getPage() + 1);
        } else if (slot == 49) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof IdentityMenuHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * @return the number of account slots per menu page
     */
    private static int countAccountsPerPage() {
        return IdentityMenuService.getAccountSlots().size();
    }

    /**
     * Returns the position of the given slot within the account slot layout.
     *
     * @param slot the clicked inventory slot
     * @return the zero-based position, or -1 if the slot is no account slot
     */
    private static int positionInAccountSlots(int slot) {
        List<Integer> slots = IdentityMenuService.getAccountSlots();
        for (int i = 0; i < slots.size(); ++i) {
            if (slots.get(i) == slot) {
                return i;
            }
        }
        return -1;
    }
}
