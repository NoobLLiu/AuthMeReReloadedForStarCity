package fr.xephi.authme.geyser;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Dynamic proxy around Floodgate's API which corrects identity-based queries for players
 * whose identity was switched:
 * <ul>
 *     <li>{@code isFloodgateId(UUID)} reports whether the player's underlying client is
 *     actually a Bedrock client instead of whether the switched identity's UUID follows
 *     the Floodgate UUID format.</li>
 *     <li>{@code getPlayer(UUID)} and {@code isFloodgatePlayer(UUID)} also find players
 *     playing under a switched identity. Floodgate stores every player by their own
 *     xuid-derived UUID, and for Floodgate-format UUIDs it skips the lookup by the
 *     identity the server actually sees — so a Bedrock player playing under a switched
 *     Bedrock identity cannot be found. The corrected lookup scans the online players
 *     for the identity the server sees them with.</li>
 * </ul>
 */
final class FloodgateApiProxy implements InvocationHandler {

    private final FloodgateApi original;
    private final SwitchedPlatformTracker platformTracker;

    private FloodgateApiProxy(FloodgateApi original, SwitchedPlatformTracker platformTracker) {
        this.original = original;
        this.platformTracker = platformTracker;
    }

    /**
     * Creates a proxied Floodgate API which corrects the identity-based queries
     * {@code isFloodgateId(UUID)}, {@code getPlayer(UUID)} and {@code isFloodgatePlayer(UUID)}.
     *
     * @param original the original API implementation
     * @param platformTracker the tracker of switched players' actual platforms
     * @return the proxied API
     */
    static FloodgateApi create(FloodgateApi original, SwitchedPlatformTracker platformTracker) {
        return (FloodgateApi) Proxy.newProxyInstance(
            FloodgateApi.class.getClassLoader(),
            new Class<?>[]{FloodgateApi.class},
            new FloodgateApiProxy(original, platformTracker));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null && args.length == 1 && args[0] instanceof UUID) {
            if (isFloodgateIdMethod(method)) {
                Boolean actualBedrock = platformTracker.getActualBedrock((UUID) args[0]);
                if (actualBedrock != null) {
                    return actualBedrock;
                }
            } else if (isGetPlayerMethod(method)) {
                return getPlayer((UUID) args[0]);
            } else if (isFloodgatePlayerMethod(method)) {
                return getPlayer((UUID) args[0]) != null;
            }
        }
        try {
            return method.invoke(original, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    /**
     * Returns the Floodgate player behind the given UUID, also when the UUID belongs to a
     * switched identity. The original lookup is tried first; when it fails for an identity
     * known to be switched, the online players are scanned for the identity the server
     * actually sees the player with.
     *
     * @param uuid the UUID to look up
     * @return the Floodgate player, or null if no online Floodgate player plays with it
     */
    private FloodgatePlayer getPlayer(UUID uuid) {
        FloodgatePlayer player = original.getPlayer(uuid);
        if (player != null || platformTracker.getActualBedrock(uuid) == null) {
            return player;
        }
        for (FloodgatePlayer candidate : original.getPlayers()) {
            if (uuid.equals(candidate.getCorrectUniqueId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns whether the given method is the {@code isFloodgateId(UUID)} query, matched
     * by name, parameter and return type to stay independent of the exact Floodgate build.
     *
     * @param method the invoked method
     * @return true if the method is the {@code isFloodgateId} UUID query
     */
    private static boolean isFloodgateIdMethod(Method method) {
        return isUuidQuery(method, "isFloodgateId", boolean.class);
    }

    /**
     * Returns whether the given method is the {@code getPlayer(UUID)} query.
     *
     * @param method the invoked method
     * @return true if the method is the {@code getPlayer} UUID query
     */
    private static boolean isGetPlayerMethod(Method method) {
        return isUuidQuery(method, "getPlayer", FloodgatePlayer.class);
    }

    /**
     * Returns whether the given method is the {@code isFloodgatePlayer(UUID)} query.
     *
     * @param method the invoked method
     * @return true if the method is the {@code isFloodgatePlayer} UUID query
     */
    private static boolean isFloodgatePlayerMethod(Method method) {
        return isUuidQuery(method, "isFloodgatePlayer", boolean.class);
    }

    private static boolean isUuidQuery(Method method, String name, Class<?> returnType) {
        return name.equals(method.getName())
            && method.getParameterCount() == 1
            && method.getParameterTypes()[0] == UUID.class
            && method.getReturnType() == returnType;
    }
}
