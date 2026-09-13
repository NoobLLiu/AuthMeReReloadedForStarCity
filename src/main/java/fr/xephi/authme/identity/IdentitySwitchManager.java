package fr.xephi.authme.identity;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.geyser.PendingLinkedRegistry;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.util.expiring.ExpiringMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import javax.inject.Inject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Manages the identity switch feature ("switch identity" in the /lg menu): validates switch
 * requests, keeps the pending switches valid for a limited time window and grants the auto
 * login after a successful identity rewrite on reconnection.
 */
public class IdentitySwitchManager {

    /** Minutes a recorded switch stays valid, counted from the moment it was initiated. */
    private static final long SWITCH_WINDOW_MINUTES = 3;

    /** Directory where pending switch files are written for the Geyser Extension to read. */
    private static final String GEYSER_SWITCH_DIR = "plugins/AuthMe/geyser-pending-switches";

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(IdentitySwitchManager.class);

    private final DataSource dataSource;
    private final PlayerCache playerCache;
    private final Messages messages;
    private final BukkitService bukkitService;
    private final PendingLinkedRegistry linkedRegistry;

    /** Pending switches, keyed by the lowercase name of the account that initiated the switch. */
    private final ExpiringMap<String, PendingSwitch> pendingBySource =
        new ExpiringMap<>(SWITCH_WINDOW_MINUTES, TimeUnit.MINUTES);

    /** Lowercase names of pending switch targets, mapped back to their initiator. */
    private final ExpiringMap<String, String> sourceByTarget =
        new ExpiringMap<>(SWITCH_WINDOW_MINUTES, TimeUnit.MINUTES);

    /** Granted auto logins after a successful identity rewrite, keyed by target lowercase name. */
    private final ExpiringMap<String, String> autoLoginByTarget =
        new ExpiringMap<>(SWITCH_WINDOW_MINUTES, TimeUnit.MINUTES);

    @Inject
    IdentitySwitchManager(DataSource dataSource, PlayerCache playerCache, Messages messages,
                          BukkitService bukkitService, PendingLinkedRegistry linkedRegistry) {
        this.dataSource = dataSource;
        this.playerCache = playerCache;
        this.messages = messages;
        this.bukkitService = bukkitService;
        this.linkedRegistry = linkedRegistry;
    }

    /**
     * Runs the validation chain for an identity switch request. If all checks pass, the pending
     * switch is recorded (valid for {@link #SWITCH_WINDOW_MINUTES} minutes) and the player is
     * disconnected with a notice to rejoin.
     *
     * @param player the player who confirmed the switch (must be logged in)
     * @param targetName the name of the account to switch to, as displayed in the menu
     */
    public void initiateSwitch(Player player, String targetName) {
        final String sourceName = player.getName();
        final String sourceLower = sourceName.toLowerCase(Locale.ROOT);
        final String targetLower = targetName == null ? "" : targetName.toLowerCase(Locale.ROOT);

        if (targetLower.equals(sourceLower)) {
            messages.send(player, MessageKey.IDENTITY_SWITCH_SELF);
            return;
        }

        final String ip = getPlayerIp(player);

        bukkitService.runTaskAsynchronously(() -> {
            PlayerAuth sourceAuth = getAuth(sourceName);
            String sourceEmail = sourceAuth == null ? null : sourceAuth.getEmail();

            if (isEmailMissing(sourceEmail)) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_NOT_BOUND);
                return;
            }

            PlayerAuth targetAuth = dataSource.getAuth(targetLower);
            if (targetAuth == null || targetAuth.getRealName() == null) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_TARGET_GONE);
                return;
            }

            UUID targetUuid = targetAuth.getUuid();
            if (targetUuid == null) {
                // Old account whose UUID has not been recorded yet: never hand out a
                // regenerated one — the account must log in once to sync its UUID first
                sendMessage(player, MessageKey.IDENTITY_SWITCH_UUID_MISSING);
                return;
            }

            if (isBedrockPlayer(player) && isFloodgateUuid(targetUuid)) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_BEDROCK_UNSUPPORTED);
                return;
            }

            String targetEmail = targetAuth.getEmail();
            if (isEmailMissing(targetEmail) || !targetEmail.equalsIgnoreCase(sourceEmail)) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_EMAIL_MISMATCH);
                return;
            }

            if (isOnline(targetAuth.getRealName())) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_TARGET_OCCUPIED, targetAuth.getRealName());
                return;
            }

            if (hasPendingForTarget(targetLower)) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_CONFLICT);
                return;
            }

            String bedrockXuid = null;
            if (isBedrockPlayer(player)) {
                bedrockXuid = getBedrockXuid(player);
            }
            UUID bedrockSourceId = bedrockUuidFromXuid(bedrockXuid);

            PendingSwitch pending = new PendingSwitch(sourceLower, targetAuth.getRealName(),
                targetUuid, ip, isFloodgateUuid(targetUuid), bedrockSourceId);

            pendingBySource.put(sourceLower, pending);
            sourceByTarget.put(targetLower, sourceLower);
            if (bedrockSourceId != null) {
                linkedRegistry.register(bedrockSourceId, pending);
            }
            logger.info(String.format("Identity switch initiated: '%s' -> '%s'", sourceName,
                targetAuth.getRealName()));

            // If the source player is a Bedrock player, write pending switch to shared
            // file for the Geyser Extension to read on reconnection. The file is also
            // written in linked mode as fallback: the extension only reads it while the
            // Floodgate linked-identity hook is inactive.
            if (bedrockXuid != null) {
                writeGeyserPendingSwitch(bedrockXuid, pending);
            }

            bukkitService.runTask(player, () -> {
                player.closeInventory();
                player.kickPlayer(messages.retrieveSingle(player, MessageKey.IDENTITY_SWITCH_SUCCESS_KICK));
            });
        });
    }

    /**
     * Manually syncs the UUID of the player's own account: the UUID the player is currently
     * connected with is recorded as the account's UUID in the database. Triggered by the
     * {@code /lg sync} command.
     *
     * @param player the player who requests the sync (must be logged in)
     */
    public void syncOwnUuid(Player player) {
        final String name = player.getName();
        final String nameLower = name.toLowerCase(Locale.ROOT);
        bukkitService.runTaskAsynchronously(() -> {
            PlayerAuth auth = dataSource.getAuth(nameLower);
            if (auth == null) {
                sendMessage(player, MessageKey.IDENTITY_SWITCH_TARGET_GONE);
                return;
            }
            UUID uuid = player.getUniqueId();
            if (uuid.equals(auth.getUuid())) {
                sendMessage(player, MessageKey.IDENTITY_SYNC_ALREADY);
                return;
            }
            auth.setUuid(uuid);
            if (dataSource.updateUuid(auth)) {
                logger.info(String.format("UUID of account '%s' manually synced to %s", name, uuid));
                sendMessage(player, MessageKey.IDENTITY_SYNC_SUCCESS, uuid.toString());
            } else {
                logger.warning("UUID sync failed for '" + name + "': the player_uuid column may not exist or is not configured. "
                    + "Check config value 'DataSource.mySQLPlayerUUID' in authme.yml");
                sendMessage(player, MessageKey.IDENTITY_SYNC_FAILED);
            }
        });
    }

    /**
     * Returns the pending switch recorded for the given account, without consuming it.
     *
     * @param sourceName the name the connecting player joined with (any casing)
     * @return the pending switch, or null if none is pending or it has expired
     */
    public PendingSwitch getPendingSwitch(String sourceName) {
        return pendingBySource.get(sourceName.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the pending switch whose <b>target</b> matches the given name, without
     * consuming it. Used when a Bedrock player reconnects through the Geyser extension:
     * the extension rewrites the login packet to the target identity, so the connection
     * arrives under the target's name and the UUID must be fixed up in the pre-login stage.
     *
     * @param name the name the connecting player joined with (any casing)
     * @return the pending switch targeting this name, or null if none is pending
     */
    public PendingSwitch getPendingSwitchByTarget(String name) {
        if (name == null) {
            return null;
        }
        String sourceLower = sourceByTarget.get(name.toLowerCase(Locale.ROOT));
        return sourceLower == null ? null : pendingBySource.get(sourceLower);
    }

    /**
     * Marks the pending switch of the given account as consumed (one-shot semantics).
     *
     * @param sourceName the name the connecting player joined with (any casing)
     */
    public void consumePendingSwitch(String sourceName) {
        removePendingSwitch(sourceName);
    }

    /**
     * Discards the pending switch of the given account.
     *
     * @param sourceName the name the connecting player joined with (any casing)
     */
    public void removePendingSwitch(String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        PendingSwitch pending = pendingBySource.get(lower);
        if (pending != null) {
            sourceByTarget.remove(pending.getTargetName());
            discardLinkedData(pending);
        }
        pendingBySource.remove(lower);
    }

    /**
     * Returns whether any pending switch targets the given account. Used to prevent two
     * players from racing onto the same identity.
     *
     * @param targetLower the lowercase name of the account to check
     * @return true if a pending switch exists for the target
     */
    public boolean hasPendingForTarget(String targetLower) {
        return sourceByTarget.get(targetLower) != null;
    }

    /**
     * Consumes the pending switch that targets the given account, if one exists.
     * This is the primary consumption path: called from {@link PlayerJoinEvent} when
     * the player actually joins with the target identity.
     *
     * @param targetNameLower the lowercase target name to look up
     * @return the consumed PendingSwitch, or null if none was found
     */
    public PendingSwitch consumePendingSwitchByTarget(String targetNameLower) {
        String sourceLower = sourceByTarget.get(targetNameLower);
        if (sourceLower == null) {
            return null;
        }
        PendingSwitch pending = pendingBySource.get(sourceLower);
        sourceByTarget.remove(targetNameLower);
        pendingBySource.remove(sourceLower);
        discardLinkedData(pending);
        return pending;
    }

    /**
     * Grants an auto login for the given account after its identity was rewritten.
     *
     * @param targetNameLower the lowercase name of the target account
     * @param ip the IP address the player reconnected from
     */
    public void markAutoLogin(String targetNameLower, String ip) {
        autoLoginByTarget.put(targetNameLower, ip);
    }

    /**
     * Consumes the auto login grant of the given account, if present.
     *
     * @param nameLower the lowercase name the player joined with
     * @param ip the IP address the player joined from
     * @return true if a grant existed and the address matches, false otherwise
     */
    public boolean consumeAutoLogin(String nameLower, String ip) {
        String grantedIp = autoLoginByTarget.get(nameLower);
        if (grantedIp == null) {
            return false;
        }
        autoLoginByTarget.remove(nameLower);
        return ip != null && ip.equals(grantedIp);
    }

    /**
     * Returns whether a player with the given name is currently online.
     *
     * @param name the player name to check (any casing)
     * @return true if a player with the name is online
     */
    public boolean isOnline(String name) {
        if (name == null) {
            return false;
        }
        for (Player online : bukkitService.getOnlinePlayers()) {
            if (online.getName() != null && online.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the given email address is set to a usable value.
     *
     * @param email the email address to check
     * @return true if the account has no usable email address
     */
    public static boolean isEmailMissing(String email) {
        return email == null || email.isEmpty() || PlayerAuth.DB_EMAIL_DEFAULT.equals(email);
    }

    /**
     * Returns whether the given UUID follows the Floodgate format, i.e. it was derived from
     * a Bedrock player's XUID.
     *
     * @param uuid the UUID to check
     * @return true if the UUID is a Floodgate (Bedrock) UUID
     */
    public static boolean isFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.toString().startsWith("00000000-0000-0000-");
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

    /**
     * Returns the IP address the given player is connected from.
     *
     * @param player the player to check
     * @return the IP address, or null if unavailable
     */
    private static String getPlayerIp(Player player) {
        return player.getAddress() == null || player.getAddress().getAddress() == null
            ? null
            : player.getAddress().getAddress().getHostAddress();
    }

    /**
     * Returns whether the given player is a Bedrock player connected through Geyser+Floodgate.
     *
     * @param player the player to check
     * @return true if the player is a Bedrock player
     */
    private boolean isBedrockPlayer(Player player) {
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                return false;
            }
            return FloodgateApi.getInstance().isFloodgateId(player.getUniqueId());
        } catch (NoClassDefFoundError | Exception e) {
            return false;
        }
    }

    /**
     * Returns the Xbox User ID (XUID) of a Bedrock player connected through Floodgate.
     *
     * @param player the Bedrock player
     * @return the XUID, or null if unavailable
     */
    private String getBedrockXuid(Player player) {
        try {
            FloodgatePlayer fgPlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
            return fgPlayer != null ? fgPlayer.getXuid() : null;
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }

    /**
     * Writes the pending switch data to a shared properties file for the Geyser Extension to read.
     * The file is named by the Bedrock player's XUID and placed in a shared directory.
     *
     * @param xuid the Bedrock player's Xbox User ID (used as the file name)
     * @param pending the pending switch data to write
     */
    private void writeGeyserPendingSwitch(String xuid, PendingSwitch pending) {
        File dir = new File(GEYSER_SWITCH_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create Geyser pending switch directory: " + dir.getAbsolutePath());
            return;
        }

        File file = new File(dir, xuid + ".properties");
        Properties props = new Properties();
        props.setProperty("sourceName", pending.getSourceName());
        props.setProperty("targetName", pending.getTargetRealName());
        props.setProperty("targetUuid", pending.getTargetUuid().toString());
        props.setProperty("ip", pending.getIp() != null ? pending.getIp() : "");
        props.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));

        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "AuthMe Geyser pending switch - auto-generated, do not edit");
            logger.info(String.format("Wrote Geyser pending switch for XUID '%s' -> '%s'",
                xuid, pending.getTargetRealName()));
        } catch (Exception e) {
            logger.warning("Could not write Geyser pending switch file for XUID '" + xuid + "': " + e.getMessage());
        }
    }

    /**
     * Discards the linked-mode data belonging to the given pending switch: the registry
     * entry serving Floodgate's linked-player query and the shared pending switch file
     * (which the Geyser Extension no longer consumes in linked mode).
     *
     * @param pending the pending switch being consumed or discarded, may be null
     */
    private void discardLinkedData(PendingSwitch pending) {
        if (pending == null) {
            return;
        }
        linkedRegistry.consume(pending.getBedrockSourceId());
        deleteGeyserPendingSwitch(pending.getBedrockSourceId());
    }

    /**
     * Derives the Floodgate UUID of a Bedrock player from its XUID, mirroring Floodgate's
     * UUID generation: the XUID is stored in the least significant bits of the UUID.
     *
     * @param xuid the Bedrock player's Xbox User ID
     * @return the Floodgate UUID, or null if the XUID is missing or not numeric
     */
    private static UUID bedrockUuidFromXuid(String xuid) {
        if (xuid == null || xuid.isEmpty()) {
            return null;
        }
        try {
            return new UUID(0L, Long.parseLong(xuid));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Deletes the pending switch file of the given Bedrock player, closing the loop in
     * linked mode: the file is only a fallback there and must not survive the switch.
     *
     * @param bedrockSourceId the Floodgate UUID of the Bedrock player who initiated the switch
     */
    private void deleteGeyserPendingSwitch(UUID bedrockSourceId) {
        if (bedrockSourceId == null) {
            return;
        }
        String xuid = String.valueOf(bedrockSourceId.getLeastSignificantBits());
        File file = new File(GEYSER_SWITCH_DIR, xuid + ".properties");
        if (file.exists() && !file.delete()) {
            logger.warning("Could not delete Geyser pending switch file: " + file.getAbsolutePath());
        }
    }
}
