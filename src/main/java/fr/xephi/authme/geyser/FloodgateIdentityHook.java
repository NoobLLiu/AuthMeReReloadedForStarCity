package fr.xephi.authme.geyser;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.HooksSettings;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.link.PlayerLink;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;

/**
 * Installs and removes the Floodgate linked-identity hook.
 * <p>
 * After an identity switch (/lg), other plugins must judge the player by the platform the
 * player is actually playing on, not by the switched identity's type. The hook:
 * <ul>
 *   <li>replaces Floodgate's {@link FloodgateApi} with a proxy that corrects
 *       {@code isFloodgateId} for switched players ({@link FloodgateApiProxy}),</li>
 *   <li>wraps Floodgate's player link with {@link PendingSwitchPlayerLink} so that a
 *       reconnecting Bedrock player is served the switched identity through Floodgate's
 *       linked-player handshake query.</li>
 * </ul>
 * Both replacements are made via reflection on Floodgate's internal holder and are
 * restored when the plugin is disabled. On any failure the original state is restored and
 * the capability marker is not written, so the Geyser Extension keeps using its own
 * rewrite mechanism (legacy mode).
 * <p>
 * Floodgate classes are only touched inside guarded blocks; their lazy resolution keeps
 * this class loadable when Floodgate is not installed.
 */
public class FloodgateIdentityHook {

    /** Marker file signalling the Geyser Extension that linked mode is active. */
    public static final String MARKER_FILE = "geyser-linked-mode.enabled";

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(FloodgateIdentityHook.class);
    private final Settings settings;
    private final File dataFolder;
    private final SwitchedPlatformTracker platformTracker;
    private final PendingLinkedRegistry linkedRegistry;

    private volatile boolean installed;
    private FloodgateApi originalApi;
    private PlayerLink originalLink;

    @Inject
    FloodgateIdentityHook(Settings settings, @DataFolder File dataFolder,
                          SwitchedPlatformTracker platformTracker,
                          PendingLinkedRegistry linkedRegistry) {
        this.settings = settings;
        this.dataFolder = dataFolder;
        this.platformTracker = platformTracker;
        this.linkedRegistry = linkedRegistry;
    }

    /**
     * Registers the hook according to the configuration and Floodgate's presence. Safe to
     * call unconditionally: without Floodgate, or when disabled in the configuration, any
     * previously installed hook is removed and the capability marker is cleared.
     *
     * @param plugin the AuthMe plugin instance
     */
    public void register(Plugin plugin) {
        if (!settings.getProperty(HooksSettings.FLOODGATE_LINKED_IDENTITY)) {
            logger.info("Floodgate linked-identity hook is disabled in the configuration");
            uninstall();
            return;
        }
        if (plugin.getServer().getPluginManager().getPlugin("floodgate") == null) {
            logger.info("Floodgate is not installed; linked-identity hook stays inactive");
            uninstall();
            return;
        }
        install();
    }

    /**
     * Returns whether the linked-identity hook is currently active, i.e. whether
     * Floodgate's API and player link are wrapped by this hook. While active, identity
     * switches initiated by Bedrock players (including switches to another Bedrock
     * identity) are served through Floodgate's linked-player query.
     *
     * @return true if the hook is installed
     */
    public boolean isLinkedModeActive() {
        return installed;
    }

    /**
     * Installs the API proxy and the player-link wrapper and writes the capability marker
     * on full success. Any failure restores the original Floodgate state.
     */
    private synchronized void install() {
        if (installed) {
            writeMarkerFile();
            return;
        }
        try {
            Field apiField = getHolderField("api");
            Field linkField = getHolderField("playerLink");

            FloodgateApi currentApi = (FloodgateApi) apiField.get(null);
            PlayerLink currentLink = (PlayerLink) linkField.get(null);

            if (currentApi == null || currentLink == null) {
                logger.warning("Floodgate has no API/player-link implementation; "
                    + "linked-identity hook not installed");
                return;
            }

            FloodgateApi apiProxy = FloodgateApiProxy.create(currentApi, platformTracker);
            PendingSwitchPlayerLink wrappedLink = new PendingSwitchPlayerLink(currentLink, linkedRegistry);

            apiField.set(null, apiProxy);
            linkField.set(null, wrappedLink);

            // Self-check: both access paths must now return the hooked instances
            if (FloodgateApi.getInstance() != apiProxy || InstanceHolder.getPlayerLink() != wrappedLink) {
                apiField.set(null, currentApi);
                linkField.set(null, currentLink);
                logger.warning("Floodgate linked-identity hook failed its self-check; "
                    + "original state restored and hook not enabled");
                return;
            }

            originalApi = currentApi;
            originalLink = currentLink;
            installed = true;
            writeMarkerFile();
            logger.info("Floodgate linked-identity hook installed: switched players are "
                + "judged by the platform they actually play on");
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            logger.info("Floodgate is not installed; linked-identity hook stays inactive");
        } catch (NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            logger.warning("Could not install the Floodgate linked-identity hook: " + e.getMessage());
        }
    }

    /**
     * Removes the hook: restores the original Floodgate API and player link and deletes
     * the capability marker. Only instances installed by this hook are replaced, so a
     * concurrent replacement by another tool is never overwritten.
     */
    public synchronized void uninstall() {
        if (installed) {
            try {
                Field apiField = getHolderField("api");
                Field linkField = getHolderField("playerLink");

                Object currentApi = apiField.get(null);
                Object currentLink = linkField.get(null);

                if (currentApi != null && Proxy.isProxyClass(currentApi.getClass()) && originalApi != null) {
                    apiField.set(null, originalApi);
                }
                if (currentLink instanceof PendingSwitchPlayerLink && originalLink != null) {
                    linkField.set(null, originalLink);
                }
                logger.info("Floodgate linked-identity hook uninstalled");
            } catch (NoClassDefFoundError | ClassNotFoundException e) {
                // Floodgate is gone: there is nothing left to restore
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.warning("Could not uninstall the Floodgate linked-identity hook: " + e.getMessage());
            } finally {
                installed = false;
                originalApi = null;
                originalLink = null;
            }
        }
        deleteMarkerFile();
    }

    /**
     * Returns the given static field of Floodgate's internal {@code InstanceHolder}.
     *
     * @param fieldName the name of the field ("api" or "playerLink")
     * @return the accessible field
     * @throws ClassNotFoundException if Floodgate is not installed
     * @throws NoSuchFieldException if the field does not exist (unexpected Floodgate build)
     * @throws IllegalAccessException if the field cannot be made accessible
     */
    private static Field getHolderField(String fieldName)
            throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        Class<?> holder = Class.forName("org.geysermc.floodgate.api.InstanceHolder");
        Field field = holder.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    private void writeMarkerFile() {
        try {
            Files.write(new File(dataFolder, MARKER_FILE).toPath(), new byte[0]);
        } catch (IOException e) {
            logger.warning("Could not write the linked-mode marker file: " + e.getMessage());
        }
    }

    private void deleteMarkerFile() {
        try {
            Files.deleteIfExists(new File(dataFolder, MARKER_FILE).toPath());
        } catch (IOException e) {
            logger.warning("Could not delete the linked-mode marker file: " + e.getMessage());
        }
    }
}
