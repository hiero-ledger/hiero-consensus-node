// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@link ClprSynchronizer} over the unary {@code sync} RPC: one request carrying this side's bundle, one response
 * carrying the peer's. The bundle is built before anything is learned about the peer, so its range always starts at
 * {@code acked_message_id + 1} — the peer's state as of the last completed cycle, which is what makes this path resend
 * messages the peer already holds. {@link ClprStreamingSynchronizer} is its two-phase replacement.
 *
 * <p>Peer selection and the per-peer failure tracking behind it are delegated to {@link ClprPeerSelector}.
 *
 * <p>Holds no mutable state of its own; the per-peer circuit breakers and reputations live in the thread-safe
 * {@link ClprPeerSelector}.
 */
@Singleton
public final class ClprUnarySynchronizer implements ClprSynchronizer {
    private static final Logger logger = LogManager.getLogger(ClprUnarySynchronizer.class);

    private final ConfigProvider configProvider;
    private final ClprBundleSubmitter bundleSubmitter;
    private final ClprStateProofManager stateProofManager;
    private final ClprLeafCertManager leafCertManager;
    private final ClprEndpointClientCache clientCache;
    private final ClprPeerSelector peerSelector;

    /**
     * @param configProvider    source of the live {@link ClprConfig}
     * @param bundleSubmitter   submits the peer's response bundle for consensus
     * @param stateProofManager builds this side's bundle
     * @param leafCertManager   supplies this node's mTLS leaf credentials
     * @param clientCache       vends the per-peer endpoint clients
     * @param peerSelector      picks the peer to dial and tracks its failures
     */
    @Inject
    public ClprUnarySynchronizer(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ClprLeafCertManager leafCertManager,
            @NonNull final ClprEndpointClientCache clientCache,
            @NonNull final ClprPeerSelector peerSelector) {
        this.configProvider = requireNonNull(configProvider);
        this.bundleSubmitter = requireNonNull(bundleSubmitter);
        this.stateProofManager = requireNonNull(stateProofManager);
        this.leafCertManager = requireNonNull(leafCertManager);
        this.clientCache = requireNonNull(clientCache);
        this.peerSelector = requireNonNull(peerSelector);
    }

    @Override
    public void synchronize(
            @NonNull final ClprChannel channel,
            @NonNull final List<ClprEndpoint> providedEndpoints,
            final long localEndpointManifestVersion,
            final long peerObservedManifestVersion) {
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled()) {
            return;
        }
        final var channelIdBytes = channel.channelId();
        final var channelId = channelIdBytes.toHex();
        // Defense-in-depth: PENDING channels have not completed commit-reveal (spec §5.1.3)
        // and CLOSED channels are terminal (spec §2.1.1). Neither is eligible for sync —
        // ClprChannelManager.initiateSync already filters these, but guard here in case
        // synchronize() is invoked directly.
        final var status = channel.status();
        if (status == ClprChannelStatus.PENDING || status == ClprChannelStatus.CLOSED) {
            logger.debug("[CLPR-SYNC-OUTBOUND] skipping ineligible channel conn={} status={}", channelId, status);
            return;
        }
        // Dial targets come from the caller (ClprChannelManager). This class is agnostic
        // to whether they were derived from the peer's manifest (flag on) or from
        // ClprLedgerConfiguration.endpoints (flag off) — that decision belongs to the caller.
        final var peerThrottles = channel.peerThrottlesOrThrow();
        final var selected = peerSelector.selectEndpoint(channel, providedEndpoints);
        if (selected == null) {
            return;
        }
        final var selectedPeer = selected.peerId();
        final var selectedEndpoint = selected.endpoint();
        logger.debug(
                "[CLPR-SYNC-OUTBOUND] selected peer conn={} peer={} status={} "
                        + "nextMsgId={} ackedMsgId={} receivedMsgId={}",
                channelId,
                selectedPeer,
                status,
                channel.nextMessageId(),
                channel.ackedMessageId(),
                channel.receivedMessageId());

        // Build outbound payload — null means no signed block snapshot yet; skip this tick.
        // The peer's reported view of OUR manifest is compared against our own version here:
        // when the peer's cache of our manifest is behind, we ask the state-proof builder to embed
        // the manifest leaf so the peer's verifyBundle refreshes its cache via Step 1b. See #335.
        // NB: this uses peerObservedManifestVersion (peer's cache of us), NOT
        // channel.endpointManifestVersion() (our cache of the peer) — a distinct axis.
        final boolean peerManifestIsStale = peerObservedManifestVersion < localEndpointManifestVersion;
        final ClprSyncPayload outboundPayload =
                buildOutboundPayload(channel, channelIdBytes, peerThrottles, peerManifestIsStale);
        if (outboundPayload == null) {
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] no outbound payload built conn={} peer={} firstMessageId={}",
                    channelId,
                    selectedPeer,
                    channel.ackedMessageId() + 1);
            return;
        }

        if (leafCertManager.isMtlsEnabled() && selectedEndpoint.tlsCertificate().length() == 0) {
            logger.warn("Skipping sync peer {} — mTLS is enabled but no tls_certificate in ClprEndpoint", selectedPeer);
            return;
        }

        // Make the gRPC sync call to the peer
        final var host = selectedEndpoint.serviceEndpoint().ipAddress();
        final var port = selectedEndpoint.serviceEndpoint().port();
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        final var timeout = Duration.ofSeconds(clprConfig.syncTimeoutSeconds());

        try {
            // Client is cached and reused per peer by the cache.
            final var client = clientCache.clientFor(
                    host, port, selectedEndpoint.tlsCertificate(), leafCertManager.leafCredentials());
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] sending request conn={} peer={} bundleBytes={}",
                    channelId,
                    selectedPeer,
                    outboundPayload.bundlePayload().length());
            final var peerResponse = client.sync(outboundPayload, timeout);
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] received response conn={} peer={} bundleBytes={}",
                    channelId,
                    selectedPeer,
                    peerResponse.bundlePayload().length());

            // Submit the peer's response bundle for consensus processing
            if (peerResponse.bundlePayload().length() > 0) {
                final var success = bundleSubmitter.submitBundle(peerResponse);
                logger.debug(
                        "[CLPR-SYNC-OUTBOUND] peer response submit attempted conn={} peer={} "
                                + "bundleBytes={} success={}",
                        channelId,
                        selectedPeer,
                        peerResponse.bundlePayload().length(),
                        success);
                if (success) {
                    peerSelector.recordSuccess(selectedPeer);
                } else {
                    peerSelector.recordFailure(selectedPeer);
                    logger.warn("Bundle submission failed for channel {} via peer {}", channelId, selectedPeer);
                }
            } else {
                // Peer had no messages for us — still a successful sync
                logger.debug("[CLPR-SYNC-OUTBOUND] peer response empty conn={} peer={}", channelId, selectedPeer);
                peerSelector.recordSuccess(selectedPeer);
            }
        } catch (final ClprEndpointClient.ClprSyncException | ClientException e) {
            // ClprSyncException: RPC/handshake failure. ClientException: mTLS channel could not
            // be built (e.g. malformed peer certificate). Both are peer-level failures for the breaker.
            peerSelector.recordFailure(selectedPeer);
            logger.error("Sync call failed for channel {} via peer {}", channelId, selectedPeer, e);
        }
    }

    /**
     * Builds the outbound {@link ClprSyncPayload} containing this node's queued messages as a
     * {@code StateProof} bundle. Returns
     * {@code null} when no signed block snapshot is available yet (e.g. during node bring-up);
     * the caller should skip the sync tick and retry on the next interval.
     */
    @Nullable
    private ClprSyncPayload buildOutboundPayload(
            @NonNull final ClprChannel channel,
            @NonNull final Bytes channelId,
            @NonNull final ClprThrottles peerThrottles,
            final boolean includeEndpointManifest) {
        final long firstMessageId = channel.ackedMessageId() + 1;
        logger.debug(
                "[CLPR-SYNC-OUTBOUND] build payload start conn={} firstMessageId={} ackedMsgId={} "
                        + "nextMsgId={} receivedMsgId={} peerMaxMessages={} peerMaxSyncBytes={} "
                        + "includeEndpointManifest={}",
                channelId,
                firstMessageId,
                channel.ackedMessageId(),
                channel.nextMessageId(),
                channel.receivedMessageId(),
                peerThrottles.maxMessagesPerBundle(),
                peerThrottles.maxSyncBytes(),
                includeEndpointManifest);
        final var bundlePayload = stateProofManager.buildSerializedBundleProof(
                channelId, firstMessageId, peerThrottles, false, includeEndpointManifest);
        if (bundlePayload == null) {
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] build payload skipped conn={} firstMessageId={}", channelId, firstMessageId);
            return null;
        }

        return ClprSyncPayload.newBuilder()
                .channelId(channelId)
                .bundlePayload(bundlePayload)
                .build();
    }
}
