package fr.xephi.authme.identity;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.service.hook.GMZCSkinCacheHook;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds and opens the identity menu (/lg): shows the current account, its bound email
 * address and all other accounts registered under the same email, which can be switched to.
 */
public class IdentityMenuService {

    /** Slots in which the switchable accounts are displayed (rows 3-5, columns 2-8). */
    private static final int[] ACCOUNT_SLOTS = {
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};
    private static final int SLOT_CURRENT = 4;
    private static final int SLOT_EMAIL = 13;
    private static final int SLOT_NO_ACCOUNTS = 22;
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_NEXT_PAGE = 53;
    private static final int ACCOUNTS_PER_PAGE = ACCOUNT_SLOTS.length;

    private final DataSource dataSource;
    private final PlayerCache playerCache;
    private final Messages messages;
    private final BukkitService bukkitService;
    private final GMZCSkinCacheHook skinCacheHook;

    @Inject
    IdentityMenuService(DataSource dataSource, PlayerCache playerCache, Messages messages,
                        BukkitService bukkitService, GMZCSkinCacheHook skinCacheHook) {
        this.dataSource = dataSource;
        this.playerCache = playerCache;
        this.messages = messages;
        this.bukkitService = bukkitService;
        this.skinCacheHook = skinCacheHook;
    }

    /**
     * Opens the identity menu for the given player. Account data is fetched asynchronously
     * before the menu is built and opened on the player's scheduler.
     *
     * @param player the player to open the menu for
     */
    public void open(Player player) {
        bukkitService.runTaskAsynchronously(() -> {
            PlayerAuth auth = getAuth(player.getName());
            String email = auth == null ? null : auth.getEmail();
            List<IdentityMenuHolder.AccountEntry> accounts = new ArrayList<>();

            if (!IdentitySwitchManager.isEmailMissing(email)) {
                String playerLower = player.getName().toLowerCase(Locale.ROOT);
                for (String name : dataSource.getAllAuthsByEmail(email)) {
                    if (name == null || name.toLowerCase(Locale.ROOT).equals(playerLower)) {
                        continue;
                    }
                    PlayerAuth accountAuth = dataSource.getAuth(name);
                    if (accountAuth == null || accountAuth.getRealName() == null) {
                        continue;
                    }
                    accounts.add(new IdentityMenuHolder.AccountEntry(
                        accountAuth.getRealName(), accountAuth.getUuid(),
                        IdentitySwitchManager.isFloodgateUuid(accountAuth.getUuid())));
                }
            }
            final String boundEmail = IdentitySwitchManager.isEmailMissing(email) ? null : email;
            final List<IdentityMenuHolder.AccountEntry> entries = accounts;
            bukkitService.runTask(player, () -> {
                if (player.isOnline()) {
                    openPage(player, boundEmail, entries, 0);
                }
            });
        });
    }

    /**
     * Builds and opens a specific page of the identity menu. Must be called on the player's
     * scheduler.
     *
     * @param player the player to open the menu for
     * @param email the bound email address, or null if not bound
     * @param accounts the switchable accounts
     * @param page the zero-based page number
     */
    public void openPage(Player player, String email, List<IdentityMenuHolder.AccountEntry> accounts,
                         int page) {
        int maxPage = Math.max(0, (accounts.size() - 1) / ACCOUNTS_PER_PAGE);
        if (page < 0 || page > maxPage) {
            return;
        }

        IdentityMenuHolder holder = new IdentityMenuHolder(email, accounts, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
            messages.retrieveSingle(player, MessageKey.IDENTITY_MENU_TITLE));
        holder.setInventory(inventory);

        // Filler
        ItemStack filler = createSimpleItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); ++slot) {
            inventory.setItem(slot, filler);
        }

        // Current account
        inventory.setItem(SLOT_CURRENT, createCurrentAccountItem(player));

        // Bound email address
        inventory.setItem(SLOT_EMAIL, createEmailItem(player, email));

        // Switchable accounts of the current page
        if (accounts.isEmpty()) {
            inventory.setItem(SLOT_NO_ACCOUNTS, createNoAccountsItem(player, email));
        } else {
            int from = page * ACCOUNTS_PER_PAGE;
            int to = Math.min(accounts.size(), from + ACCOUNTS_PER_PAGE);
            for (int i = from; i < to; ++i) {
                inventory.setItem(ACCOUNT_SLOTS[i - from], createAccountItem(player, accounts.get(i)));
            }
        }

        // Navigation
        if (page > 0) {
            inventory.setItem(SLOT_PREV_PAGE, createSimpleItem(Material.ARROW,
                ChatColor.GOLD + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_PAGE_PREV)));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT_PAGE, createSimpleItem(Material.SPECTRAL_ARROW,
                ChatColor.GOLD + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_PAGE_NEXT)));
        }
        inventory.setItem(SLOT_CLOSE, createSimpleItem(Material.BARRIER,
            ChatColor.RED + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_CLOSE)));

        player.openInventory(inventory);
    }

    /**
     * @return the slots in which switchable accounts are displayed
     */
    public static List<Integer> getAccountSlots() {
        return Arrays.stream(ACCOUNT_SLOTS).boxed().toList();
    }

    /**
     * Creates the head item of the current account (glowing).
     *
     * @param player the viewing player
     * @return the created item
     */
    private ItemStack createCurrentAccountItem(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (!skinCacheHook.applySkin(meta, player)) {
            meta.setOwningPlayer(player);
        }
        meta.setDisplayName(ChatColor.AQUA + player.getName());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_CURRENT));
        lore.add(ChatColor.DARK_GRAY + "UUID: " + player.getUniqueId());
        meta.setLore(lore);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates the item showing the bound email address.
     *
     * @param player the viewing player
     * @param email the bound email address, or null if not bound
     * @return the created item
     */
    private ItemStack createEmailItem(Player player, String email) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_EMAIL));
        List<String> lore = new ArrayList<>();
        lore.add(email == null
            ? ChatColor.RED + messages.retrieveSingle(player, MessageKey.IDENTITY_EMAIL_NOT_BOUND)
            : ChatColor.WHITE + email);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates the placeholder item shown when no other account exists under the email.
     *
     * @param player the viewing player
     * @param email the bound email address, or null if not bound
     * @return the created item
     */
    private ItemStack createNoAccountsItem(Player player, String email) {
        return createSimpleItem(Material.BARRIER, ChatColor.RED
            + messages.retrieveSingle(player, MessageKey.IDENTITY_MENU_NO_OTHER_ACCOUNTS));
    }

    /**
     * Creates the head item of a switchable account.
     *
     * @param player the viewing player
     * @param entry the account to display
     * @return the created item
     */
    private ItemStack createAccountItem(Player player, IdentityMenuHolder.AccountEntry entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        UUID uuid = entry.getUuid();
        if (uuid != null) {
            if (!skinCacheHook.applySkin(meta, uuid)) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            }
        }
        String edition = "";
        if (uuid != null) {
            edition = entry.isBedrock()
                ? ChatColor.BLUE + " [" + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_BEDROCK) + "]"
                : ChatColor.GREEN + " [" + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_JAVA) + "]";
        }
        meta.setDisplayName(ChatColor.YELLOW + entry.getRealName() + edition);
        List<String> lore = new ArrayList<>();
        if (uuid == null) {
            // Old account without a recorded UUID: never fall back to a regenerated one
            lore.add(messages.retrieveSingle(player, MessageKey.IDENTITY_SWITCH_UUID_MISSING));
        } else {
            lore.add(ChatColor.DARK_GRAY + "UUID: " + uuid);
            lore.add(ChatColor.GRAY + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_CLICK_SWITCH));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates a simple item with the given material and display name.
     *
     * @param material the material
     * @param name the display name
     * @return the created item
     */
    private ItemStack createSimpleItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Fetches the auth of the given account, preferring the login cache over the database.
     *
     * @param name the name of the account (any casing)
     * @return the auth, or null if not registered
     */
    private PlayerAuth getAuth(String name) {
        PlayerAuth auth = playerCache.getAuth(name);
        return auth != null ? auth : dataSource.getAuth(name);
    }
}
