package fr.xephi.authme.identity;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.security.PasswordSecurity;
import fr.xephi.authme.security.crypts.HashedPassword;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.util.expiring.ExpiringMap;
import org.bukkit.entity.Player;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Manages the account bindings of the identity switch feature (/lg): binding a new account
 * (verified by entering that account's password), unbinding, and validating bindings before
 * a switch. A binding becomes invalid as soon as the target account's password no longer
 * matches the snapshot taken at bind time; such bindings are removed automatically.
 * <p>
 * Also keeps the "chat input mode" used by Java players: after clicking the bind button in
 * the menu, the next chat message is captured (not broadcast) and interpreted as
 * {@code <account> <password>}.
 */
public class IdentityBindManager {

    /** Minutes a chat-input bind session stays active after the bind button was clicked. */
    private static final long CHAT_INPUT_MINUTES = 5;

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(IdentityBindManager.class);

    private final DataSource dataSource;
    private final PlayerCache playerCache;
    private final Messages messages;
    private final BukkitService bukkitService;
    private final PasswordSecurity passwordSecurity;
    private final AccountBindingStore bindingStore;

    /** Players waiting for a chat message of the form "<account> <password>", by lowercase name. */
    private final ExpiringMap<String, Boolean> pendingChatInputs =
        new ExpiringMap<>(CHAT_INPUT_MINUTES, TimeUnit.MINUTES);

    @Inject
    IdentityBindManager(DataSource dataSource, PlayerCache playerCache, Messages messages,
                        BukkitService bukkitService, PasswordSecurity passwordSecurity,
                        AccountBindingStore bindingStore) {
        this.dataSource = dataSource;
        this.playerCache = playerCache;
        this.messages = messages;
        this.bukkitService = bukkitService;
        this.passwordSecurity = passwordSecurity;
        this.bindingStore = bindingStore;
    }

    /**
     * Binds the given account to the player's account. The ownership of the target account
     * is verified by checking the supplied password against the account's stored password.
     * On success both accounts can be switched between from the /lg menu.
     *
     * @param player the initiating player (must be logged in)
     * @param targetName the name of the account to bind
     * @param password the password of the account to bind, for verification
     */
    public void bindAccount(Player player, String targetName, String password) {
        final String sourceLower = player.getName().toLowerCase(Locale.ROOT);
        final String targetTrimmed = targetName == null ? "" : targetName.trim();
        final String targetLower = targetTrimmed.toLowerCase(Locale.ROOT);
        final String passwordToCheck = password == null ? "" : password;

        bukkitService.runTaskAsynchronously(() -> {
            if (targetLower.isEmpty() || targetLower.equals(sourceLower)) {
                messages.send(player, MessageKey.IDENTITY_BIND_SELF);
                return;
            }

            PlayerAuth targetAuth = dataSource.getAuth(targetLower);
            if (targetAuth == null || targetAuth.getRealName() == null) {
                messages.send(player, MessageKey.IDENTITY_BIND_TARGET_NOT_FOUND, targetTrimmed);
                return;
            }

            if (!passwordSecurity.comparePassword(passwordToCheck, targetAuth.getPassword(),
                    targetAuth.getRealName())) {
                logger.info(String.format("Account bind rejected for '%s': wrong password for target '%s'",
                    player.getName(), targetAuth.getRealName()));
                messages.send(player, MessageKey.IDENTITY_BIND_WRONG_PASSWORD);
                return;
            }

            if (bindingStore.isBound(sourceLower, targetLower)) {
                messages.send(player, MessageKey.IDENTITY_BIND_ALREADY, targetAuth.getRealName());
                return;
            }

            PlayerAuth sourceAuth = getAuth(player.getName());
            if (sourceAuth == null || sourceAuth.getPassword() == null) {
                logger.warning("Account bind failed for '" + player.getName() + "': own auth not found");
                messages.send(player, MessageKey.IDENTITY_BIND_FAILED);
                return;
            }

            bindingStore.addBinding(sourceLower, targetLower,
                sourceAuth.getPassword(), targetAuth.getPassword());
            logger.info(String.format("Account bound: '%s' <-> '%s'", player.getName(), targetAuth.getRealName()));
            messages.send(player, MessageKey.IDENTITY_BIND_SUCCESS, targetAuth.getRealName());
        });
    }

    /**
     * Removes the binding between the player's account and the given account.
     *
     * @param player the initiating player (must be logged in)
     * @param targetName the name of the bound account to unbind
     */
    public void unbindAccount(Player player, String targetName) {
        final String sourceLower = player.getName().toLowerCase(Locale.ROOT);
        final String targetTrimmed = targetName == null ? "" : targetName.trim();
        final String targetLower = targetTrimmed.toLowerCase(Locale.ROOT);

        bukkitService.runTaskAsynchronously(() -> {
            if (bindingStore.removeBinding(sourceLower, targetLower)) {
                logger.info(String.format("Account unbound: '%s' <-> '%s'", player.getName(), targetLower));
                messages.send(player, MessageKey.IDENTITY_UNBIND_SUCCESS, targetTrimmed);
            } else {
                messages.send(player, MessageKey.IDENTITY_UNBIND_NOT_BOUND, targetTrimmed);
            }
        });
    }

    /**
     * Returns the valid bound accounts of the given player, i.e. the accounts that can be
     * switched to from the /lg menu. Bindings whose target account no longer exists or whose
     * target password was changed after the binding are removed automatically and excluded.
     * Must be called from an async task.
     *
     * @param playerName the name of the player whose bound accounts to list
     * @return the valid bound accounts (never null)
     */
    public List<IdentityMenuHolder.AccountEntry> getValidBoundAccounts(String playerName) {
        String nameLower = playerName.toLowerCase(Locale.ROOT);
        List<IdentityMenuHolder.AccountEntry> result = new ArrayList<>();
        for (AccountBindingStore.AccountBinding binding : bindingStore.getBindingsFor(nameLower)) {
            String otherLower = binding.getOther(nameLower);
            PlayerAuth otherAuth = dataSource.getAuth(otherLower);
            if (otherAuth == null || otherAuth.getRealName() == null) {
                bindingStore.removeBinding(nameLower, otherLower);
                logger.info(String.format("Removed account binding '%s' <-> '%s': account no longer exists",
                    nameLower, otherLower));
                continue;
            }
            if (!isSnapshotCurrent(binding.getSnapshotOf(otherLower), otherAuth.getPassword())) {
                bindingStore.removeBinding(nameLower, otherLower);
                logger.info(String.format("Removed account binding '%s' <-> '%s': password of '%s' was "
                    + "changed after the binding", nameLower, otherLower, otherLower));
                continue;
            }
            result.add(new IdentityMenuHolder.AccountEntry(otherAuth.getRealName(), otherAuth.getUuid(),
                IdentitySwitchManager.isFloodgateUuid(otherAuth.getUuid())));
        }
        return result;
    }

    /**
     * Validates that a binding exists between the source and the target account and that the
     * target's password still matches the snapshot taken at bind time. A binding whose target
     * password was changed is removed automatically and reported as expired.
     *
     * @param player the player requesting the switch
     * @param sourceLower the lowercase name of the initiating account
     * @param targetAuth the auth of the target account (freshly fetched)
     * @return true if the switch may proceed, false otherwise (a message was sent to the player)
     */
    public boolean validateSwitchBinding(Player player, String sourceLower, PlayerAuth targetAuth) {
        String targetLower = targetAuth.getNickname() != null
            ? targetAuth.getNickname()
            : targetAuth.getRealName().toLowerCase(Locale.ROOT);

        AccountBindingStore.AccountBinding binding = bindingStore.getBinding(sourceLower, targetLower);
        if (binding == null) {
            sendMessage(player, MessageKey.IDENTITY_SWITCH_NOT_BOUND);
            return false;
        }

        if (!isSnapshotCurrent(binding.getSnapshotOf(targetLower), targetAuth.getPassword())) {
            bindingStore.removeBinding(sourceLower, targetLower);
            logger.info(String.format("Removed account binding '%s' <-> '%s' while switching: password of "
                + "'%s' was changed after the binding", sourceLower, targetLower, targetLower));
            sendMessage(player, MessageKey.IDENTITY_BIND_EXPIRED, targetAuth.getRealName());
            return false;
        }
        return true;
    }

    /**
     * Puts the given player into chat input mode for binding a new account: the menu is
     * closed and the next chat message is captured as {@code <account> <password>}.
     *
     * @param player the player who clicked the bind button in the menu
     */
    public void beginChatInput(Player player) {
        pendingChatInputs.put(player.getName().toLowerCase(Locale.ROOT), Boolean.TRUE);
        // Close the menu one tick later: closing during the inventory click event is unreliable
        bukkitService.scheduleSyncDelayedTask(() -> {
            if (player.isOnline()) {
                player.closeInventory();
            }
        });
        messages.send(player, MessageKey.IDENTITY_BIND_CHAT_PROMPT);
    }

    /**
     * Returns whether the given player is in chat input mode for binding a new account.
     *
     * @param name the name of the player (any casing)
     * @return true if the next chat message will be captured as bind input
     */
    public boolean isChatInputActive(String name) {
        return pendingChatInputs.get(name.toLowerCase(Locale.ROOT)) != null;
    }

    /**
     * Handles a chat message of a player in chat input mode: {@code cancel} (or {@code 取消})
     * aborts the input, {@code <account> <password>} performs the bind, anything else
     * re-prompts for the correct format. Called from the async chat event.
     *
     * @param player the player in chat input mode
     * @param message the chat message
     */
    public void handleChatInput(Player player, String message) {
        String nameLower = player.getName().toLowerCase(Locale.ROOT);
        if (!isChatInputActive(nameLower)) {
            return;
        }

        String trimmed = message == null ? "" : message.trim();
        if ("cancel".equalsIgnoreCase(trimmed) || "取消".equals(trimmed)) {
            pendingChatInputs.remove(nameLower);
            messages.send(player, MessageKey.IDENTITY_BIND_CHAT_CANCELLED);
            return;
        }

        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) {
            messages.send(player, MessageKey.IDENTITY_BIND_CHAT_INVALID);
            return;
        }
        String account = trimmed.substring(0, spaceIdx).trim();
        String password = trimmed.substring(spaceIdx + 1).trim();
        if (account.isEmpty() || password.isEmpty()) {
            messages.send(player, MessageKey.IDENTITY_BIND_CHAT_INVALID);
            return;
        }

        pendingChatInputs.remove(nameLower);
        bindAccount(player, account, password);
    }

    /**
     * Returns whether the given current password hash still matches the snapshot taken at
     * bind time.
     *
     * @param snapshot the password snapshot recorded when the binding was created
     * @param current the account's current password hash
     * @return true if the password was not changed since the binding
     */
    private static boolean isSnapshotCurrent(HashedPassword snapshot, HashedPassword current) {
        if (snapshot == null || current == null) {
            return false;
        }
        return Objects.equals(snapshot.getHash(), current.getHash())
            && Objects.equals(snapshot.getSalt(), current.getSalt());
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

    /**
     * Sends a message without tags to the given player on the main thread.
     *
     * @param player the player to message
     * @param key the message key
     */
    private void sendMessage(Player player, MessageKey key) {
        bukkitService.runTask(player, () -> {
            if (player.isOnline()) {
                messages.send(player, key);
            }
        });
    }

    /**
     * Sends a message with a single replacement to the given player on the main thread.
     *
     * @param player the player to message
     * @param key the message key
     * @param replacement the value for the message tag
     */
    private void sendMessage(Player player, MessageKey key, String replacement) {
        bukkitService.runTask(player, () -> {
            if (player.isOnline()) {
                messages.send(player, key, replacement);
            }
        });
    }
}
