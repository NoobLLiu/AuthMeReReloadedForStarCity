package com.authme.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.geysermc.geyser.api.extension.ExtensionLogger;

/**
 * Bridges Java server transfers to Bedrock transfers for Geyser connections.
 *
 * <p>When the downstream Java server sends a {@code ClientboundTransferPacket}, Geyser
 * fires {@link ServerTransferEvent}. By default Geyser does not know what Bedrock target
 * to use, so Bedrock players would just be disconnected. This listener reads the original
 * Bedrock join address from the current connection and forwards it as the Bedrock
 * transfer target.</p>
 */
public final class TransferBridgeListener {

    private final ExtensionLogger logger;

    public TransferBridgeListener(ExtensionLogger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onServerTransfer(ServerTransferEvent event) {
        try {
            if (!(event.connection() instanceof GeyserConnection connection)) {
                return;
            }

            if (event.bedrockHost() != null && !event.bedrockHost().isBlank() && event.bedrockPort() > 0) {
                return;
            }

            String originalHost;
            int originalPort;
            try {
                originalHost = connection.joinAddress();
                originalPort = connection.joinPort();
            } catch (Exception e) {
                logger.warning("AuthMe transfer bridge: unable to read original join address for connection");
                return;
            }

            if (originalHost == null || originalHost.isBlank()) {
                logger.warning("AuthMe transfer bridge: original Bedrock host is blank");
                return;
            }

            String host = originalHost;
            if (host.startsWith("[")) {
                host = host.substring(1);
            }
            if (host.endsWith("]")) {
                host = host.substring(0, host.length() - 1);
            }

            event.bedrockHost(host);
            event.bedrockPort(originalPort);

            logger.info("AuthMe transfer bridge: forwarding Bedrock transfer -> " + host + ":" + originalPort
                + " (java target: " + event.host() + ":" + event.port() + ")");
        } catch (Exception e) {
            logger.error("AuthMe transfer bridge: failed to set Bedrock transfer target", e);
        }
    }
}
