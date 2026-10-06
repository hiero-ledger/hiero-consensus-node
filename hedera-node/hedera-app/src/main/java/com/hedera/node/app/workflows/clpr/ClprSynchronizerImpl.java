// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implementation of the {@link ClprSynchronizer} interface that handles the synchronization
 * of CLPR (Consensus Layer Peer Routing) messages between nodes. This class is responsible
 * for coordinating message exchange with peer nodes to ensure consistency and reliability
 * in the distributed network.
 * <p>
 * Responsibilities:
 * - Handles synchronization with eligible peer nodes based on channel states and
 *   endpoint selection criteria.
 * - Manages outbound payload generation using state proof bundles.
 * - Performs reputation-weighted peer selection and failure tracking through circuit breakers.
 * - Submits received peer responses for consensus processing via the internal bundle submitter.
 * <p>
 * Thread Safety:
 * - This class is thread-safe as it leverages concurrent collections and appropriate
 *   synchronization mechanisms for shared resources such as circuit breakers and peer reputations.
 * <p>
 * Error Handling and Resilience:
 * - Tracks sync failures with circuit breakers and only excludes failing peers when
 *   peer exclusion is enabled.
 * - Tracks peer reputation to favor reliable peers while maintaining fairness across available options.
 * - Logs errors and warnings during synchronization to aid in debug and monitoring.
 */
@Singleton
public class ClprSynchronizerImpl implements ClprSynchronizer {
    private static final Logger logger = LogManager.getLogger(ClprSynchronizerImpl.class);

    private final ConfigProvider configProvider;
    private final ClprBundleSubmitter bundleSubmitter;
    private final NetworkInfo networkInfo;
    private final ClprStateProofManager stateProofManager;
    private final ClprLeafCertManager leafCertManager;
    private final ClprEndpointClientCache clientCache;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, PeerReputation> peerReputations = new ConcurrentHashMap<>();

    @Inject
    public ClprSynchronizerImpl(
            final ConfigProvider configProvider,
            final ClprBundleSubmitter bundleSubmitter,
            final NetworkInfo networkInfo,
            final ClprStateProofManager stateProofManager,
            final ClprLeafCertManager leafCertManager,
            final ClprEndpointClientCache clientCache) {
        this.configProvider = requireNonNull(configProvider);
        this.bundleSubmitter = requireNonNull(bundleSubmitter);
        this.stateProofManager = requireNonNull(stateProofManager);
        this.networkInfo = requireNonNull(networkInfo);
        this.leafCertManager = requireNonNull(leafCertManager);
        this.clientCache = requireNonNull(clientCache);
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
        // Cap by the peer's max_peer_endpoints throttle (spec §3.10.5).
        final var peerThrottles = channel.peerThrottlesOrThrow();
        final List<ClprEndpoint> endpoints = capByPeerLimit(providedEndpoints, peerThrottles);
        if (endpoints.isEmpty()) {
            logger.warn("[CLPR-SYNC-OUTBOUND] no endpoints available for sync conn={} status={}", channelId, status);
            return;
        }
        final NodeIdentity thisNodeIdentity = getNodeIdentity();
        final var endpointsById = new HashMap<String, ClprEndpoint>();
        for (final var ep : endpoints) {
            final var svc = ep.serviceEndpoint();
            if (svc == null) {
                logger.warn("[CLPR-SYNC-OUTBOUND] endpoint missing serviceEndpoint conn={} endpoint={}", channelId, ep);
                continue;
            }
            if (thisNodeIdentity.isSelf(svc.ipAddress(), svc.port())) {
                logger.debug(
                        "[CLPR-SYNC-OUTBOUND] skipping self endpoint conn={} endpoint={}:{}",
                        channelId,
                        svc.ipAddress(),
                        svc.port());
                continue;
            }
            final var peerId = svc.ipAddress() + ":" + svc.port();
            endpointsById.put(peerId, ep);
        }

        final var selectedPeer = selectPeer(endpointsById.keySet());
        if (selectedPeer == null) {
            logger.warn(
                    "[CLPR-SYNC-OUTBOUND] failed to select peer conn={} originalEndpoints={} candidatePeers={}",
                    channelId,
                    endpoints.size(),
                    endpointsById.size());
            return;
        }
        logger.debug(
                "[CLPR-SYNC-OUTBOUND] selected peer conn={} peer={} candidates={} status={} "
                        + "nextMsgId={} ackedMsgId={} receivedMsgId={}",
                channelId,
                selectedPeer,
                endpointsById.size(),
                status,
                channel.nextMessageId(),
                channel.ackedMessageId(),
                channel.receivedMessageId());

        final var selectedEndpoint = endpointsById.get(selectedPeer);
        final var circuitBreaker = getCircuitBreaker(selectedPeer);
        final var reputation = getReputation(selectedPeer);

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
                    circuitBreaker.recordSuccess();
                    reputation.recordSuccess();
                } else {
                    circuitBreaker.recordFailure();
                    reputation.recordFailure();
                    logger.warn("Bundle submission failed for channel {} via peer {}", channelId, selectedPeer);
                }
            } else {
                // Peer had no messages for us — still a successful sync
                logger.debug("[CLPR-SYNC-OUTBOUND] peer response empty conn={} peer={}", channelId, selectedPeer);
                circuitBreaker.recordSuccess();
                reputation.recordSuccess();
            }
        } catch (final ClprEndpointClient.ClprSyncException | ClientException e) {
            // ClprSyncException: RPC/handshake failure. ClientException: mTLS channel could not
            // be built (e.g. malformed peer certificate). Both are peer-level failures for the breaker.
            circuitBreaker.recordFailure();
            reputation.recordFailure();
            logger.error("Sync call failed for channel {} via peer {}", channelId, selectedPeer, e);
        }
    }

    /**
     * Truncates {@code endpoints} at {@code peerThrottles.maxPeerEndpoints} (spec §3.10.5).
     * Zero cap means no limit.
     */
    @NonNull
    private static List<ClprEndpoint> capByPeerLimit(
            @NonNull final List<ClprEndpoint> endpoints, @NonNull final ClprThrottles peerThrottles) {
        final int cap = peerThrottles.maxPeerEndpoints();
        if (cap > 0 && endpoints.size() > cap) {
            return endpoints.subList(0, cap);
        }
        return endpoints;
    }

    private NodeIdentity getNodeIdentity() {
        final var configuration = configProvider.getConfiguration();
        return new NodeIdentity(
                configuration.getConfigData(GrpcConfig.class),
                configuration.getConfigData(ClprConfig.class).mtlsPort(),
                networkInfo.selfNodeInfo());
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

    /**
     * Selects a peer endpoint for sync using reputation-weighted random selection.
     * Higher-reputation peers are more likely to be chosen, but all candidates
     * have some chance. When peer exclusion is enabled, peers with open circuit
     * breakers are filtered out. Returns null if no peer is available.
     *
     * @param peerEndpointIds the set of known peer endpoint IDs
     * @return the selected peer endpoint ID, or null if none available
     */
    String selectPeer(@NonNull final Collection<String> peerEndpointIds) {
        if (peerEndpointIds.isEmpty()) {
            logger.warn("[CLPR-SYNC-OUTBOUND] selectPeer called with no candidate endpoints");
            return null;
        }

        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        final boolean peerExclusionEnabled = clprConfig.syncPeerExclusionEnabled();

        // Optionally filter to peers whose circuit breaker allows requests.
        final var candidates = new ArrayList<String>();
        final var weights = new ArrayList<Double>();
        double totalWeight = 0.0;

        for (final var peerId : peerEndpointIds) {
            if (peerExclusionEnabled && !getCircuitBreaker(peerId).allowRequest()) {
                logger.debug("[CLPR-SYNC-OUTBOUND] peer excluded by circuit breaker peer={}", peerId);
                continue;
            }
            final var rep = getReputation(peerId);
            final double weight = rep.score();
            candidates.add(peerId);
            weights.add(weight);
            totalWeight += weight;
        }

        if (candidates.isEmpty()) {
            logger.warn(
                    "[CLPR-SYNC-OUTBOUND] no selectable peer candidates peerExclusionEnabled={} inputCandidates={}",
                    peerExclusionEnabled,
                    peerEndpointIds.size());
            return null;
        }

        // Single candidate — no randomization needed
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        // Weighted random selection
        final double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cumulative = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights.get(i);
            if (roll < cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.getLast();
    }

    /**
     * Returns the reputation tracker for a peer endpoint, creating one if needed.
     */
    @NonNull
    PeerReputation getReputation(@NonNull final String peerEndpointId) {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        return peerReputations.computeIfAbsent(
                peerEndpointId, id -> new PeerReputation(Duration.ofSeconds(clprConfig.reputationDecaySeconds())));
    }

    /**
     * Returns the circuit breaker for a peer endpoint, creating one if needed.
     */
    @NonNull
    CircuitBreaker getCircuitBreaker(@NonNull final String peerEndpointId) {
        // Read retryMaxAttempts fresh from config on every recordFailure(), so a network-wide
        // override via fileUpdate(APP_PROPERTIES) takes effect on live breakers without
        // requiring node restart or peer-endpoint churn. cooldownDuration is locked at the
        // breaker's first construction — acceptable since it only governs OPEN→HALF_OPEN
        // transitions and changes mid-life would race the openedAt instant in unhelpful ways.
        return circuitBreakers.computeIfAbsent(peerEndpointId, id -> {
            final var bootConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
            return new CircuitBreaker(
                    () -> configProvider
                            .getConfiguration()
                            .getConfigData(ClprConfig.class)
                            .retryMaxAttempts(),
                    Duration.ofSeconds(bootConfig.circuitBreakerCooldownSeconds()));
        });
    }
}
