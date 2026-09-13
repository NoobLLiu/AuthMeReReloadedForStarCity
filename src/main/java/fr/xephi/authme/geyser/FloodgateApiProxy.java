package fr.xephi.authme.geyser;

import org.geysermc.floodgate.api.FloodgateApi;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Dynamic proxy around Floodgate's API which corrects {@code isFloodgateId(UUID)} for
 * players whose identity was switched: it reports whether the player's underlying client
 * is actually a Bedrock client instead of whether the switched identity's UUID follows
 * the Floodgate UUID format.
 * <p>
 * {@code isFloodgatePlayer(UUID)} needs no correction: Floodgate's internal player map is
 * already keyed correctly in both switch directions (linked Bedrock players are keyed by
 * their Java UUID; Java players on Bedrock identities are absent from the map).
 */
final class FloodgateApiProxy implements InvocationHandler {

    private final FloodgateApi original;
    private final SwitchedPlatformTracker platformTracker;

    private FloodgateApiProxy(FloodgateApi original, SwitchedPlatformTracker platformTracker) {
        this.original = original;
        this.platformTracker = platformTracker;
    }

    /**
     * Creates a proxied Floodgate API which overrides the {@code isFloodgateId} query.
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
        if (isFloodgateIdMethod(method) && args != null && args.length == 1
                && args[0] instanceof UUID) {
            Boolean actualBedrock = platformTracker.getActualBedrock((UUID) args[0]);
            if (actualBedrock != null) {
                return actualBedrock;
            }
        }
        try {
            return method.invoke(original, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    /**
     * Returns whether the given method is the {@code isFloodgateId(UUID)} query, matched
     * by name, parameter and return type to stay independent of the exact Floodgate build.
     *
     * @param method the invoked method
     * @return true if the method is the {@code isFloodgateId} UUID query
     */
    private static boolean isFloodgateIdMethod(Method method) {
        return "isFloodgateId".equals(method.getName())
            && method.getParameterCount() == 1
            && method.getParameterTypes()[0] == UUID.class
            && method.getReturnType() == boolean.class;
    }
}
