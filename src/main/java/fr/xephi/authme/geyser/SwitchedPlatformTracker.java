package fr.xephi.authme.geyser;

import fr.xephi.authme.util.expiring.ExpiringMap;

import javax.inject.Inject;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the actual client platform of players whose identity was switched via the /lg
 * identity switch. Keyed by the UUID the player currently plays with (the switched
 * identity's UUID), the value tells whether the underlying client is a Bedrock client.
 * <p>
 * Entries are registered when a pending switch is consumed (pre-login or join) and are
 * removed when the player quits. The expiration is only a safety net for entries of
 * players that never fired a quit event (e.g. after a server crash).
 */
public class SwitchedPlatformTracker {

    /** Time an entry is kept as a safety net; normally entries are removed on quit. */
    private static final long ENTRY_EXPIRATION_HOURS = 12;

    private final ExpiringMap<UUID, Boolean> actualBedrockByUuid =
        new ExpiringMap<>(ENTRY_EXPIRATION_HOURS, TimeUnit.HOURS);

    @Inject
    SwitchedPlatformTracker() {
    }

    /**
     * Records the actual client platform behind the given UUID.
     *
     * @param uuid the UUID the player currently plays with
     * @param actualBedrock true if the underlying client is a Bedrock client
     */
    public void register(UUID uuid, boolean actualBedrock) {
        if (uuid != null) {
            actualBedrockByUuid.put(uuid, actualBedrock);
        }
    }

    /**
     * Returns the recorded actual client platform behind the given UUID.
     *
     * @param uuid the UUID to look up
     * @return true if the client is a Bedrock client, false if it is a Java client, or
     *         null if the UUID is not tracked
     */
    public Boolean getActualBedrock(UUID uuid) {
        return uuid == null ? null : actualBedrockByUuid.get(uuid);
    }

    /**
     * Removes the tracking entry of the given UUID.
     *
     * @param uuid the UUID to stop tracking
     */
    public void unregister(UUID uuid) {
        if (uuid != null) {
            actualBedrockByUuid.remove(uuid);
        }
    }
}
