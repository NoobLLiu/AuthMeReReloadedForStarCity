package fr.xephi.authme.geyser;

import fr.xephi.authme.identity.PendingSwitch;
import fr.xephi.authme.util.expiring.ExpiringMap;

import javax.inject.Inject;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Registry of pending identity switches initiated by Bedrock players, keyed by the
 * Bedrock player's Floodgate UUID (the UUID Floodgate derives from the player's XUID).
 * <p>
 * When the Bedrock player reconnects, Floodgate asks its player-link implementation
 * whether the Bedrock UUID is linked to a Java account; {@link PendingSwitchPlayerLink}
 * answers that query from this registry so the connection is presented with the switched
 * identity. Entries are consumed together with the pending switch they belong to and
 * expire after the switch window as a safety net.
 */
public class PendingLinkedRegistry {

    /** Matches the expiry of the pending switch files shared with the Geyser Extension. */
    private static final long ENTRY_EXPIRATION_MINUTES = 5;

    private final ExpiringMap<UUID, PendingSwitch> pendingByBedrockId =
        new ExpiringMap<>(ENTRY_EXPIRATION_MINUTES, TimeUnit.MINUTES);

    @Inject
    PendingLinkedRegistry() {
    }

    /**
     * Records a pending switch for the given Bedrock UUID.
     *
     * @param bedrockId the Floodgate UUID of the Bedrock player who initiated the switch
     * @param pending the pending switch
     */
    public void register(UUID bedrockId, PendingSwitch pending) {
        if (bedrockId != null && pending != null) {
            pendingByBedrockId.put(bedrockId, pending);
        }
    }

    /**
     * Returns the pending switch recorded for the given Bedrock UUID, without consuming it.
     *
     * @param bedrockId the Floodgate UUID to look up
     * @return the pending switch, or null if none is recorded
     */
    public PendingSwitch get(UUID bedrockId) {
        return bedrockId == null ? null : pendingByBedrockId.get(bedrockId);
    }

    /**
     * Consumes (removes) the pending switch recorded for the given Bedrock UUID.
     *
     * @param bedrockId the Floodgate UUID whose entry should be removed
     * @return the consumed pending switch, or null if none was recorded
     */
    public PendingSwitch consume(UUID bedrockId) {
        if (bedrockId == null) {
            return null;
        }
        PendingSwitch pending = pendingByBedrockId.get(bedrockId);
        pendingByBedrockId.remove(bedrockId);
        return pending;
    }
}
