package fr.xephi.authme.listener;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.geyser.SwitchedPlatformTracker;
import fr.xephi.authme.identity.IdentitySwitchManager;
import fr.xephi.authme.identity.PendingSwitch;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import javax.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/**
 * Rewrites the identity of a player who reconnects within the identity switch window: the
 * name and UUID of the connection are replaced with the ones of the target account, so that
 * the server (player data, permissions, other plugins) treats the player as the target.
 * <p>
 * Uses Paper's profile rewriting on {@link AsyncPlayerPreLoginEvent}. On servers without the
 * Paper API, the ProtocolLib fallback ({@code LoginStartRewriteAdapter}) takes over instead.
 */
public class PreLoginIdentityListener implements Listener {

    private static final boolean PAPER_PROFILE_SUPPORTED;
    private static Method getPlayerProfileMethod;
    private static Method setPlayerProfileMethod;
    private static Method profileSetNameMethod;
    private static Method profileSetIdMethod;

    static {
        boolean available = false;
        try {
            Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            getPlayerProfileMethod = AsyncPlayerPreLoginEvent.class.getMethod("getPlayerProfile");
            setPlayerProfileMethod = AsyncPlayerPreLoginEvent.class.getMethod("setPlayerProfile", profileClass);
            profileSetNameMethod = profileClass.getMethod("setName", String.class);
            profileSetIdMethod = profileClass.getMethod("setId", UUID.class);
            available = true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Server software without the Paper profile API; ProtocolLib fallback is used instead
        }
        PAPER_PROFILE_SUPPORTED = available;
    }

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(PreLoginIdentityListener.class);
    private final IdentitySwitchManager identitySwitchManager;
    private final Messages messages;
    private final SwitchedPlatformTracker platformTracker;

    @Inject
    PreLoginIdentityListener(IdentitySwitchManager identitySwitchManager, Messages messages,
                             SwitchedPlatformTracker platformTracker) {
        this.identitySwitchManager = identitySwitchManager;
        this.messages = messages;
        this.platformTracker = platformTracker;
    }

    /**
     * Returns whether the server provides Paper's player profile API, which allows rewriting
     * the name and UUID of a connection in {@link AsyncPlayerPreLoginEvent}.
     *
     * @return true if the Paper profile API is available
     */
    public static boolean isPaperProfileSupported() {
        return PAPER_PROFILE_SUPPORTED;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !PAPER_PROFILE_SUPPORTED) {
            return;
        }

        String sourceLower = event.getName().toLowerCase(Locale.ROOT);
        PendingSwitch pending = identitySwitchManager.getPendingSwitch(sourceLower);
        if (pending == null) {
            // Bedrock players reconnecting through the Geyser extension arrive under the
            // target's name (the extension rewrote the login packet): look up by target.
            // Offline servers recompute the UUID from the name, so it must be rewritten here.
            pending = identitySwitchManager.getPendingSwitchByTarget(event.getName());
            if (pending == null) {
                return;
            }
        }

        String ip = event.getAddress().getHostAddress();
        if (pending.getIp() == null || !pending.getIp().equals(ip)) {
            // Different address than the one the switch was initiated from: keep the original identity
            return;
        }

        if (identitySwitchManager.isOnline(pending.getTargetRealName())) {
            identitySwitchManager.removePendingSwitch(sourceLower);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                messages.retrieveSingle(sourceLower, MessageKey.IDENTITY_SWITCH_OCCUPIED_ON_REJOIN,
                    pending.getTargetRealName()));
            return;
        }

        try {
            Object profile = getPlayerProfileMethod.invoke(event);
            profileSetNameMethod.invoke(profile, pending.getTargetRealName());
            profileSetIdMethod.invoke(profile, pending.getTargetUuid());
            setPlayerProfileMethod.invoke(event, profile);
        } catch (IllegalAccessException | InvocationTargetException e) {
            logger.logException("Could not rewrite identity profile of '" + sourceLower + "'", e);
            return;
        }

        platformTracker.register(pending.getTargetUuid(), pending.isBedrockSource());

        // Don't consume the PendingSwitch or mark auto-login here: for Bedrock players
        // the Paper profile rewrite is ignored by Floodgate's Player creation, so we must
        // defer the decision to PlayerJoinEvent where the actual Player identity is known.
        logger.info(String.format("Rewrote login identity: '%s' -> '%s' (%s)",
            event.getName(), pending.getTargetRealName(), pending.getTargetUuid()));
    }
}
