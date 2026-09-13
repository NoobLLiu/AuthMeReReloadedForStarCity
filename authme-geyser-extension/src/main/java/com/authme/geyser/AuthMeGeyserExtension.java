package com.authme.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Geyser Extension entry point for AuthMe identity switch support.
 * <p>
 * This extension intercepts Bedrock player logins at the Geyser level and applies pending
 * identity switches written by AuthMe's {@code IdentitySwitchManager}. When a Bedrock player
 * reconnects after initiating an identity switch, this extension modifies the Geyser session's
 * Java-side username and UUID so that Floodgate creates the Player with the target identity.
 * <p>
 * <b>Communication mechanism:</b> AuthMe writes pending switch files to
 * {@code plugins/AuthMe/geyser-pending-switches/{xuid}.properties}. This extension reads
 * and consumes those files on reconnection.
 * <p>
 * <b>Lifecycle:</b> Geyser extensions do not have onEnable/onDisable methods. The loader
 * only instantiates the main class and registers it on the event bus. All initialization is
 * therefore done in {@link GeyserPostInitializeEvent} (fired once Geyser is fully initialized,
 * right before Bedrock players can connect) and cleanup in {@link GeyserShutdownEvent}.
 */
public class AuthMeGeyserExtension implements Extension {

    private static final long CLEANUP_INTERVAL_MINUTES = 2;

    private PendingSwitchStore pendingSwitchStore;
    private IdentitySwitchListener identitySwitchListener;
    private ScheduledExecutorService cleanupScheduler;

    /**
     * Fired when Geyser has completed initializing. The Geyser API is fully available
     * at this stage, and no Bedrock player has connected yet.
     */
    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        ExtensionLogger logger = logger();

        Path authMeDir = resolveAuthMeDirectory();
        logger.info("AuthMe Geyser Extension: enabling (AuthMe dir: " + authMeDir + ")");

        // Initialize the shared store
        pendingSwitchStore = new PendingSwitchStore(authMeDir, logger);

        // Register the identity switch event listener
        identitySwitchListener = new IdentitySwitchListener(pendingSwitchStore, logger);
        eventBus().register(identitySwitchListener);

        // Register the Java->Bedrock transfer bridge so Bedrock players are transferred instead of disconnected
        eventBus().register(new TransferBridgeListener(logger));

        // Schedule periodic cleanup of expired switch files
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "authme-geyser-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(
            pendingSwitchStore::cleanupExpired,
            CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES
        );

        logger.info("AuthMe Geyser Extension: enabled. Listening for Bedrock identity switches.");
    }

    /**
     * Fired when Geyser is shutting down.
     */
    @Subscribe
    public void onGeyserShutdown(GeyserShutdownEvent event) {
        ExtensionLogger logger = logger();
        logger.info("AuthMe Geyser Extension: disabling...");

        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
        }

        logger.info("AuthMe Geyser Extension: disabled.");
    }

    /**
     * Resolves the AuthMe plugin directory ({@code plugins/AuthMe}) on the Minecraft
     * server this Geyser instance runs on. AuthMe writes pending switch files to
     * {@code plugins/AuthMe/geyser-pending-switches/} relative to the server root,
     * so the directory must match AuthMe's location exactly.
     */
    private Path resolveAuthMeDirectory() {
        // On Spigot/Paper the Minecraft server root is the process working directory,
        // and AuthMe resolves its relative paths against it.
        Path workDir = Path.of(System.getProperty("user.dir", "."));
        Path candidate = workDir.resolve("plugins").resolve("AuthMe");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }

        // Walk up from this extension's data folder and look for plugins/AuthMe.
        // Spigot layout: <server>/plugins/Geyser-Spigot/extensions/<extension-id>/
        // (going up only two levels from the data folder would yield the Geyser-Spigot
        // folder, not the server root — hence the explicit search.)
        Path dir = dataFolder();
        while (dir != null) {
            candidate = dir.resolve("plugins").resolve("AuthMe");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }

        // Fallback: default layout under the working directory. Pending switch files
        // only appear once AuthMe writes them, so a not-yet-existing folder is fine.
        return workDir.resolve("plugins").resolve("AuthMe");
    }
}
