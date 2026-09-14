package fr.xephi.authme.service.hook;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft-dependency hook for GMZCSkinCache plugin.
 * Uses reflection to access the PlayerSkinService API so that AuthMe can be compiled
 * and loaded without GMZCSkinCache present at runtime.
 */
public class GMZCSkinCacheHook {

    private static final String SERVICE_CLASS = "cn.gmzc.skincache.api.PlayerSkinService";

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(GMZCSkinCacheHook.class);
    private Object skinService;
    private Method applyByUuid;
    private Method applyByPlayer;
    private boolean available;

    public GMZCSkinCacheHook() {
        try {
            Class<?> serviceClass = Class.forName(SERVICE_CLASS);
            RegisteredServiceProvider<?> registration =
                Bukkit.getServicesManager().getRegistration(serviceClass);
            if (registration != null) {
                skinService = registration.getProvider();
                applyByUuid = serviceClass.getMethod("apply", SkullMeta.class, UUID.class);
                applyByPlayer = serviceClass.getMethod("apply", SkullMeta.class, Player.class);
                available = true;
                logger.info("Hooked successfully into GMZCSkinCache");
            }
        } catch (ClassNotFoundException e) {
            // GMZCSkinCache not installed — this is expected for soft dependency
        } catch (Exception e) {
            logger.warning("Failed to hook into GMZCSkinCache: " + e.getMessage());
        }
    }

    /**
     * @return true if GMZCSkinCache is available and the service was loaded
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Applies cached skin texture to the given SkullMeta using the player's UUID.
     * Falls back gracefully if the cache lookup fails.
     *
     * @param meta the skull meta to apply the skin to
     * @param uuid the player UUID to look up in cache
     * @return true if the skin was applied successfully
     */
    public boolean applySkin(SkullMeta meta, UUID uuid) {
        if (!available || meta == null || uuid == null) {
            return false;
        }
        try {
            return (boolean) applyByUuid.invoke(skinService, meta, uuid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Applies the skin from an online player's current profile.
     * Falls back to UUID cache if the live profile is unavailable.
     *
     * @param meta the skull meta to apply the skin to
     * @param player the online player
     * @return true if the skin was applied successfully
     */
    public boolean applySkin(SkullMeta meta, Player player) {
        if (!available || meta == null || player == null) {
            return false;
        }
        try {
            return (boolean) applyByPlayer.invoke(skinService, meta, player);
        } catch (Exception e) {
            return false;
        }
    }
}
