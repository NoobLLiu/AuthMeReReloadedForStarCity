package com.authme.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.api.network.RemoteServer;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Listens for Bedrock player logins at the Geyser level and applies pending identity
 * switches written by AuthMe's IdentitySwitchManager.
 * <p>
 * <b>How it works:</b> AuthMe writes a pending switch file (keyed by the Bedrock player's
 * XUID) when a Bedrock player initiates a switch to a Java account. When that player
 * reconnects through Geyser, this listener rewrites the connection identity <b>before</b>
 * Geyser connects to the Java server:
 * <ol>
 *   <li>The {@code MinecraftProtocol} profile is replaced with the target Java identity
 *       (name + UUID), so the LoginStart packet carries the target identity.</li>
 *   <li>The session's {@code RemoteServer} is wrapped with {@link AuthType#OFFLINE} so
 *       {@code GeyserSessionAdapter} does not append encrypted Floodgate data to the
 *       handshake hostname. Without Floodgate data on the connection, the Floodgate
 *       plugin treats it as a regular (non-Bedrock) connection and does not override
 *       the identity with {@code prefix + gamertag} / XUID-based UUID.</li>
 * </ol>
 * The AuthMe plugin then fixes up the UUID in AsyncPlayerPreLoginEvent (offline servers
 * recompute the offline UUID from the name) and completes the switch on PlayerJoinEvent.
 * <p>
 * The spoofed connection address is preserved automatically: Geyser's LocalSession
 * presents the Bedrock player's real IP to the server, so AuthMe's IP check still passes.
 */
public class IdentitySwitchListener {

    private final PendingSwitchStore store;
    private final ExtensionLogger logger;

    public IdentitySwitchListener(PendingSwitchStore store, ExtensionLogger logger) {
        this.store = store;
        this.logger = logger;
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        try {
            handleSessionLogin(event);
        } catch (Exception e) {
            logger.error("Error in AuthMe identity switch listener: " + e.getMessage(), e);
        }
    }

    private void handleSessionLogin(SessionLoginEvent event) {
        if (store.isLinkedModeEnabled()) {
            // AuthMe's Floodgate linked-identity hook serves this switch through Floodgate's
            // linked-player query during the handshake; leave the session and the pending
            // file untouched (AuthMe consumes them on the Java side)
            return;
        }
        GeyserConnection connection = event.connection();
        if (connection == null) {
            return;
        }
        String xuid = connection.xuid();
        if (xuid == null || xuid.isEmpty()) {
            return;
        }
        PendingSwitchStore.PendingSwitchData pending = store.getAndConsume(xuid);
        if (pending == null) {
            return;
        }

        String bedrockName = connection.bedrockUsername();
        if (isFloodgateUuid(pending.getTargetUuid())) {
            // Switching to another Bedrock account is not supported: the Floodgate data
            // (which carries the real XUID) must stay intact for such connections.
            logger.warning("AuthMe identity switch: target '" + pending.getTargetName()
                + "' is a Bedrock identity; switching between Bedrock accounts is not supported yet");
            return;
        }

        logger.info("AuthMe identity switch: applying Bedrock switch for XUID '" + xuid
            + "' (original: '" + bedrockName + "', target: '" + pending.getTargetName()
            + "', uuid: " + pending.getTargetUuid() + ")");

        if (applyTargetIdentity(connection, pending)) {
            logger.info("AuthMe identity switch: connection '" + bedrockName
                + "' will join the Java server as '" + pending.getTargetName()
                + "' (Floodgate bypassed)");
        } else {
            logger.warning("AuthMe identity switch: could not rewrite the session identity for XUID '"
                + xuid + "'. The identity switch may not work.");
        }
    }

    /**
     * Rewrites the session so the downstream connection uses the target Java identity.
     *
     * @param connection the Geyser session (GeyserSession)
     * @param pending the pending switch data
     * @return true if the session was successfully rewritten
     */
    private boolean applyTargetIdentity(Object connection, PendingSwitchStore.PendingSwitchData pending) {
        try {
            // 1. Rewrite the MinecraftProtocol profile (name + UUID) used for the LoginStart packet
            Object newProfile = createGameProfile(connection, pending.getTargetUuid(), pending.getTargetName());
            Field protocolField = findField(connection.getClass(), "protocol");
            if (protocolField == null) {
                logger.warning("AuthMe identity switch: 'protocol' field not found on "
                    + connection.getClass().getName());
                return false;
            }
            Object protocol = protocolField.get(connection);
            if (protocol == null) {
                logger.warning("AuthMe identity switch: session protocol is not set up yet");
                return false;
            }
            Field profileField = findField(protocol.getClass(), "profile");
            if (profileField == null) {
                logger.warning("AuthMe identity switch: 'profile' field not found on "
                    + protocol.getClass().getName());
                return false;
            }
            profileField.set(protocol, newProfile);

            // 2. Wrap the RemoteServer with OFFLINE auth type so GeyserSessionAdapter does
            //    not append encrypted Floodgate data to the handshake hostname. Without that
            //    data, the Floodgate plugin on the server treats this connection as a
            //    regular Java connection instead of a Bedrock one.
            Field remoteServerField = findField(connection.getClass(), "remoteServer");
            if (remoteServerField == null) {
                logger.warning("AuthMe identity switch: 'remoteServer' field not found on "
                    + connection.getClass().getName());
                return false;
            }
            RemoteServer currentServer = (RemoteServer) remoteServerField.get(connection);
            if (currentServer == null || currentServer.authType() != AuthType.FLOODGATE) {
                // Nothing to bypass (e.g. ONLINE/OFFLINE auth): the LoginStart rewrite above is enough
                return true;
            }
            remoteServerField.set(connection, new OfflineAuthRemoteServer(currentServer));

            return true;
        } catch (Exception e) {
            logger.warning("AuthMe identity switch: failed to rewrite session identity: " + e);
            return false;
        }
    }

    /**
     * Creates an mcprotocollib GameProfile (UUID, name) via reflection: the mcprotocollib
     * classes are not visible to extensions at compile time.
     */
    private Object createGameProfile(Object session, UUID uuid, String name) throws Exception {
        ClassLoader classLoader = session.getClass().getClassLoader();
        Class<?> gameProfileClass = Class.forName(
            "org.geysermc.mcprotocollib.auth.GameProfile", false, classLoader);
        return gameProfileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);
    }

    /**
     * Same format check as AuthMe's IdentitySwitchManager.isFloodgateUuid: Bedrock-derived
     * UUIDs start with 00000000-0000-0000-.
     */
    private static boolean isFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.toString().startsWith("00000000-0000-0000-");
    }

    /**
     * Finds a declared field by name, searching the class hierarchy.
     */
    private static Field findField(Class<?> clazz, String name) throws IllegalAccessException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // continue with the superclass
            }
        }
        return null;
    }

    /**
     * Delegating RemoteServer wrapper that reports {@link AuthType#OFFLINE}. Used to make
     * GeyserSessionAdapter skip the Floodgate handshake data for this one connection.
     */
    private static final class OfflineAuthRemoteServer implements RemoteServer {
        private final RemoteServer delegate;

        OfflineAuthRemoteServer(RemoteServer delegate) {
            this.delegate = delegate;
        }

        @Override
        public String address() {
            return delegate.address();
        }

        @Override
        public int port() {
            return delegate.port();
        }

        @Override
        public int protocolVersion() {
            return delegate.protocolVersion();
        }

        @Override
        public String minecraftVersion() {
            return delegate.minecraftVersion();
        }

        @Override
        public AuthType authType() {
            return AuthType.OFFLINE;
        }

        @Override
        public boolean resolveSrv() {
            return delegate.resolveSrv();
        }
    }
}
