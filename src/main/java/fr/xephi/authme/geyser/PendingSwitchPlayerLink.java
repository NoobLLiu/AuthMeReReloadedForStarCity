package fr.xephi.authme.geyser;

import fr.xephi.authme.identity.PendingSwitch;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Wrapper around Floodgate's player-link implementation that answers the linked-player
 * query for players with a pending identity switch: when a Bedrock player who initiated
 * a switch reconnects, Floodgate's handshake asks {@link #getLinkedPlayer(UUID)} for the
 * Bedrock UUID and, upon receiving a result, presents the player to the server with the
 * linked Java identity. This is what makes plugins judge the player as a Bedrock player
 * playing under a Java identity.
 * <p>
 * All other queries are delegated to the original implementation. Note that
 * {@link #isEnabled()} and {@link #isEnabledAndAllowed()} return {@code true} regardless
 * of the link settings: Floodgate's handshake query path may bail out otherwise, and the
 * switch must be served even when account linking is disabled on the server. Actual
 * linking remains locked via {@link #isAllowLinking()}, which delegates to the original.
 */
public class PendingSwitchPlayerLink implements PlayerLink {

    private final PlayerLink delegate;
    private final PendingLinkedRegistry linkedRegistry;

    PendingSwitchPlayerLink(PlayerLink delegate, PendingLinkedRegistry linkedRegistry) {
        this.delegate = delegate;
        this.linkedRegistry = linkedRegistry;
    }

    @Override
    public CompletableFuture<LinkedPlayer> getLinkedPlayer(UUID bedrockId) {
        PendingSwitch pending = linkedRegistry.get(bedrockId);
        if (pending == null) {
            return delegate.getLinkedPlayer(bedrockId);
        }
        return CompletableFuture.completedFuture(
            LinkedPlayer.of(pending.getTargetRealName(), pending.getTargetUuid(), bedrockId));
    }

    @Override
    public CompletableFuture<Boolean> isLinkedPlayer(UUID bedrockId) {
        if (linkedRegistry.get(bedrockId) != null) {
            return CompletableFuture.completedFuture(true);
        }
        return delegate.isLinkedPlayer(bedrockId);
    }

    @Override
    public boolean isEnabled() {
        // Always true: the handshake query path may bail out otherwise
        return true;
    }

    @Override
    public boolean isEnabledAndAllowed() {
        // Defensive override: serves whichever check Floodgate's handshake query performs
        return true;
    }

    @Override
    public boolean isAllowLinking() {
        // Keep the original setting: actual linking (bind to a Java account) stays locked
        return delegate.isAllowLinking();
    }

    @Override
    public void load() {
        delegate.load();
    }

    @Override
    public CompletableFuture<Void> linkPlayer(UUID bedrockId, UUID javaId, String javaUsername) {
        return delegate.linkPlayer(bedrockId, javaId, javaUsername);
    }

    @Override
    public CompletableFuture<Void> unlinkPlayer(UUID bedrockId) {
        return delegate.unlinkPlayer(bedrockId);
    }

    @Override
    public CompletableFuture<?> createLinkRequest(UUID bedrockId, String javaUsername, String javaUuid) {
        return delegate.createLinkRequest(bedrockId, javaUsername, javaUuid);
    }

    @Override
    public CompletableFuture<LinkRequestResult> verifyLinkRequest(UUID bedrockId, String code,
                                                                  String javaUsername, String javaUuid) {
        return delegate.verifyLinkRequest(bedrockId, code, javaUsername, javaUuid);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public long getVerifyLinkTimeout() {
        return delegate.getVerifyLinkTimeout();
    }

    @Override
    public void stop() {
        delegate.stop();
    }
}
