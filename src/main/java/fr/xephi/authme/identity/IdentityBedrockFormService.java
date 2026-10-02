package fr.xephi.authme.identity;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.service.BukkitService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bedrock edition variant of the identity menu (/lg): presents the same content as the chest
 * menu of {@link IdentityMenuService} (current account with UUID, bound email address, all
 * other accounts under the same email with edition tag, paging and close) as a native Bedrock
 * form. Only used for players connected through Geyser + Floodgate.
 */
public class IdentityBedrockFormService {

    /** Accounts displayed per form page, mirroring the chest menu layout. */
    private static final int ACCOUNTS_PER_PAGE = 21;

    private final DataSource dataSource;
    private final PlayerCache playerCache;
    private final Messages messages;
    private final BukkitService bukkitService;
    private final IdentitySwitchManager identitySwitchManager;
    private final IdentityMenuService menuService;

    @Inject
    IdentityBedrockFormService(DataSource dataSource, PlayerCache playerCache, Messages messages,
                               BukkitService bukkitService, IdentitySwitchManager identitySwitchManager,
                               IdentityMenuService menuService) {
        this.dataSource = dataSource;
        this.playerCache = playerCache;
        this.messages = messages;
        this.bukkitService = bukkitService;
        this.identitySwitchManager = identitySwitchManager;
        this.menuService = menuService;
    }

    /**
     * Opens the identity menu as a Bedrock form for the given player. Account data is fetched
     * asynchronously before the first page is sent. Falls back to the chest menu when the
     * Floodgate form could not be built or delivered.
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
                    sendPage(player, boundEmail, entries, 0);
                }
            });
        });
    }

    /**
     * Builds and sends one page of the identity form. May be called from the main thread or
     * from a Floodgate response handler; falls back to the chest menu on any failure.
     *
     * @param player the player to send the form to
     * @param email the bound email address, or null if not bound
     * @param accounts the switchable accounts
     * @param page the zero-based page number
     */
    private void sendPage(Player player, String email,
                          List<IdentityMenuHolder.AccountEntry> accounts, int page) {
        try {
            doSendPage(player, email, accounts, page);
        } catch (NoClassDefFoundError | Exception e) {
            // Floodgate or Cumulus classes unavailable, or the form could not be delivered:
            // the chest menu still works for Bedrock players, so fall back to it
            menuService.open(player);
        }
    }

    /**
     * Builds and sends one page of the identity form without error handling.
     *
     * @param player the player to send the form to
     * @param email the bound email address, or null if not bound
     * @param accounts the switchable accounts
     * @param page the zero-based page number
     */
    private void doSendPage(Player player, String email,
                            List<IdentityMenuHolder.AccountEntry> accounts, int page) {
        int maxPage = Math.max(0, (accounts.size() - 1) / ACCOUNTS_PER_PAGE);
        if (page < 0 || page > maxPage) {
            return;
        }

        // Button index -> action; the response handler only reports the clicked index
        List<Runnable> actions = new ArrayList<>();
        SimpleForm.Builder builder = SimpleForm.builder()
            .title(messages.retrieveSingle(player, MessageKey.IDENTITY_MENU_TITLE))
            .content(buildContent(player, email, accounts, page, maxPage));

        int from = page * ACCOUNTS_PER_PAGE;
        int to = Math.min(accounts.size(), from + ACCOUNTS_PER_PAGE);
        for (int i = from; i < to; ++i) {
            IdentityMenuHolder.AccountEntry entry = accounts.get(i);
            builder.button(buildAccountButton(player, entry));
            actions.add(() -> identitySwitchManager.initiateSwitch(player, entry.getRealName()));
        }

        if (page > 0) {
            builder.button(ChatColor.GOLD
                + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_PAGE_PREV));
            actions.add(() -> sendPage(player, email, accounts, page - 1));
        }
        builder.button(ChatColor.RED
            + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_CLOSE));
        actions.add(() -> { });
        if (page < maxPage) {
            builder.button(ChatColor.GOLD
                + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_PAGE_NEXT));
            actions.add(() -> sendPage(player, email, accounts, page + 1));
        }

        builder.validResultHandler(response -> handleClick(player, actions, response.clickedButtonId()));
        builder.closedResultHandler(() -> { });

        FloodgatePlayer fgPlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (fgPlayer == null || !fgPlayer.sendForm(builder.build())) {
            menuService.open(player);
        }
    }

    /**
     * Handles a form response: runs the action of the clicked button on the main thread, as
     * Floodgate invokes the handler on a network thread.
     *
     * @param player the player who responded
     * @param actions the actions mapped to the form buttons
     * @param clickedButtonId the index of the clicked button
     */
    private void handleClick(Player player, List<Runnable> actions, int clickedButtonId) {
        bukkitService.runTask(player, () -> {
            if (!player.isOnline() || clickedButtonId < 0 || clickedButtonId >= actions.size()) {
                return;
            }
            actions.get(clickedButtonId).run();
        });
    }

    /**
     * Builds the form content: the current account with its UUID, the bound email address,
     * the page indicator (only when there is more than one page) and the no-account notice,
     * mirroring the chest menu items.
     *
     * @param player the viewing player
     * @param email the bound email address, or null if not bound
     * @param accounts the switchable accounts
     * @param page the zero-based page number
     * @param maxPage the highest page number
     * @return the content text
     */
    private String buildContent(Player player, String email,
                                List<IdentityMenuHolder.AccountEntry> accounts,
                                int page, int maxPage) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.AQUA).append(player.getName()).append('\n')
            .append(ChatColor.GRAY)
            .append(messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_CURRENT)).append('\n')
            .append(ChatColor.DARK_GRAY).append("UUID: ").append(player.getUniqueId()).append('\n')
            .append('\n')
            .append(ChatColor.YELLOW)
            .append(messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_EMAIL)).append('\n')
            .append(email == null
                ? ChatColor.RED + messages.retrieveSingle(player, MessageKey.IDENTITY_EMAIL_NOT_BOUND)
                : ChatColor.WHITE + email)
            .append('\n');
        if (maxPage > 0) {
            sb.append('\n')
                .append(messages.retrieveSingle(player, MessageKey.IDENTITY_FORM_PAGE,
                    String.valueOf(page + 1), String.valueOf(maxPage + 1)))
                .append('\n');
        }
        if (accounts.isEmpty()) {
            sb.append('\n').append(ChatColor.RED)
                .append(messages.retrieveSingle(player, MessageKey.IDENTITY_MENU_NO_OTHER_ACCOUNTS));
        } else {
            sb.append('\n')
                .append(messages.retrieveSingle(player, MessageKey.IDENTITY_FORM_CHOOSE));
        }
        return sb.toString();
    }

    /**
     * Builds the text of a switchable account button, mirroring the head item of the chest
     * menu: name with edition tag, UUID and the switch hint (or the missing-UUID notice).
     *
     * @param player the viewing player
     * @param entry the account to display
     * @return the button text
     */
    private String buildAccountButton(Player player, IdentityMenuHolder.AccountEntry entry) {
        UUID uuid = entry.getUuid();
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.YELLOW).append(entry.getRealName());
        if (uuid == null) {
            // Old account without a recorded UUID: never fall back to a regenerated one
            sb.append('\n')
                .append(messages.retrieveSingle(player, MessageKey.IDENTITY_SWITCH_UUID_MISSING));
        } else {
            sb.append(entry.isBedrock()
                ? ChatColor.BLUE + " [" + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_BEDROCK) + "]"
                : ChatColor.GREEN + " [" + messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_JAVA) + "]");
            sb.append('\n')
                .append(ChatColor.DARK_GRAY).append("UUID: ").append(uuid).append('\n')
                .append(ChatColor.GRAY)
                .append(messages.retrieveSingle(player, MessageKey.IDENTITY_LORE_CLICK_SWITCH));
        }
        return sb.toString();
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
