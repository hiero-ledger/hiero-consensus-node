// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.net.InetAddresses;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
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
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Chooses which peer endpoint an outbound sync dials, and keeps the per-peer failure tracking that drives that choice.
 *
 * <p>Thread-safe: the per-peer state lives in concurrent maps, and the trackers themselves are thread-safe.
 */
@Singleton
public final class ClprPeerSelector {

    private static final Logger logger = LogManager.getLogger(ClprPeerSelector.class);

    private final ConfigProvider configProvider;
    private final NetworkInfo networkInfo;
    private final LongSupplier nanoTime;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, PeerReputation> peerReputations = new ConcurrentHashMap<>();
    private final Map<String, RetrySchedule> retrySchedules = new ConcurrentHashMap<>();

    /**
     * A peer endpoint chosen for one sync.
     *
     * @param peerId   the {@code host:port} key the peer's failure tracking is filed under
     * @param endpoint the endpoint to dial
     */
    record SelectedPeer(@NonNull String peerId, @NonNull ClprEndpoint endpoint) {}

    /**
     * @param configProvider source of the live {@link ClprConfig}: peer exclusion, breaker and reputation settings
     * @param networkInfo    supplies this node's own endpoints, so it is never selected as a peer
     */
    @Inject
    public ClprPeerSelector(@NonNull final ConfigProvider configProvider, @NonNull final NetworkInfo networkInfo) {
        this(configProvider, networkInfo, System::nanoTime);
    }

    ClprPeerSelector(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final LongSupplier nanoTime) {
        this.configProvider = requireNonNull(configProvider);
        this.networkInfo = requireNonNull(networkInfo);
        this.nanoTime = requireNonNull(nanoTime);
    }

    /**
     * Picks the endpoint to dial for one sync: drops malformed and self endpoints, then chooses among the rest by
     * reputation.
     *
     * <p>No {@code max_peer_endpoints} cap is applied here: that throttle bounds how many peer endpoints this ledger
     * stores, and every source of dial targets is already truncated to it when stored (spec §2.4.2).
     *
     * @param channel           the Channel being synced
     * @param providedEndpoints the dial targets the caller chose
     * @return the chosen peer, or {@code null} when no candidate is available
     */
    @Nullable
    SelectedPeer selectEndpoint(
            @NonNull final ClprChannel channel, @NonNull final List<ClprEndpoint> providedEndpoints) {
        final var channelId = channel.channelId().toHex();
        if (providedEndpoints.isEmpty()) {
            logger.warn("[CLPR-SYNC-OUTBOUND] no endpoints available for sync channel={}", channelId);
            return null;
        }
        final NodeIdentity thisNodeIdentity = getNodeIdentity();
        final var endpointsById = new HashMap<String, ClprEndpoint>();
        for (final var ep : providedEndpoints) {
            final var svc = ep.serviceEndpoint();
            if (svc == null) {
                logger.warn(
                        "[CLPR-SYNC-OUTBOUND] endpoint missing serviceEndpoint channel={} endpoint={}", channelId, ep);
                continue;
            }
            final var host = canonicalHost(svc.ipAddress());
            if (thisNodeIdentity.isSelf(host, svc.port())) {
                logger.debug(
                        "[CLPR-SYNC-OUTBOUND] skipping self endpoint channel={} endpoint={}:{}",
                        channelId,
                        host,
                        svc.port());
                continue;
            }
            final var peerId = host + ":" + svc.port();
            endpointsById.put(peerId, ep);
        }

        if (endpointsById.isEmpty()) {
            logger.warn(
                    "[CLPR-SYNC-OUTBOUND] no endpoints to select. channel={} providedEndpoints={}",
                    channelId,
                    providedEndpoints.size());
            return null;
        }
        final var selectedPeer = selectPeerByReputation(channelId, endpointsById.keySet());
        return selectedPeer == null ? null : new SelectedPeer(selectedPeer, endpointsById.get(selectedPeer));
    }

    private static String canonicalHost(@NonNull final String host) {
        try {
            return InetAddresses.toAddrString(InetAddresses.forString(host));
        } catch (final IllegalArgumentException e) {
            // The client cache reports malformed destinations; retain their original spelling for retry tracking.
            return host;
        }
    }

    /**
     * Records a sync with {@code peerId} that went as expected.
     */
    void recordSuccess(@NonNull final String peerId) {
        retrySchedules.remove(peerId);
        getCircuitBreaker(peerId).recordSuccess();
        getReputation(peerId).recordSuccess();
    }

    /**
     * Records a sync with {@code peerId} that failed, counting against its breaker and reputation.
     */
    void recordFailure(@NonNull final String peerId) {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        final long initialDelayMs = Math.max(0, clprConfig.retryInitialDelayMs());
        final long maxDelayMs = Math.max(0, clprConfig.retryMaxDelayMs());
        retrySchedules.compute(peerId, (id, previous) -> {
            final long delayMs;
            if (previous == null) {
                delayMs = Math.min(initialDelayMs, maxDelayMs);
            } else {
                // Saturate before doubling, including when the configured cap changes between failures.
                delayMs =
                        previous.delayMs() > maxDelayMs / 2 ? maxDelayMs : Math.min(previous.delayMs() * 2, maxDelayMs);
            }
            return new RetrySchedule(delayMs, nanoTime.getAsLong());
        });
        final var breaker = getCircuitBreaker(peerId);
        final boolean wasOpen = breaker.state() == CircuitBreaker.State.OPEN;
        breaker.recordFailure();
        getReputation(peerId).recordFailure();
        // Logged on the transition only: while the breaker stays open, every sync tick would otherwise repeat it.
        if (!wasOpen && breaker.state() == CircuitBreaker.State.OPEN) {
            logger.warn("[CLPR-SYNC-OUTBOUND] circuit breaker opened peer={}", peerId);
        }
    }

    private NodeIdentity getNodeIdentity() {
        final var configuration = configProvider.getConfiguration();
        return new NodeIdentity(configuration.getConfigData(GrpcConfig.class), networkInfo.selfNodeInfo());
    }

    /**
     * Selects a peer endpoint for sync using reputation-weighted random selection. Higher-reputation peers are more
     * likely to be chosen, but all candidates have some chance. Failed peers wait for their bounded retry delay,
     * independently of peer exclusion. When peer exclusion is enabled, peers with open circuit breakers are also
     * filtered out. Returns null if no peer is available.
     *
     * @param channelId       the Channel ID in hex, for logging
     * @param peerEndpointIds the set of known peer endpoint IDs
     * @return the selected peer endpoint ID, or null if none available
     */
    @Nullable
    private String selectPeerByReputation(
            @NonNull final String channelId, @NonNull final Collection<String> peerEndpointIds) {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        final boolean peerExclusionEnabled = clprConfig.syncPeerExclusionEnabled();
        final long now = nanoTime.getAsLong();

        // Optionally filter to peers whose circuit breaker allows requests.
        final var candidates = new ArrayList<String>();
        final var weights = new ArrayList<Double>();
        double totalWeight = 0.0;

        for (final var peerId : peerEndpointIds) {
            final var retry = retrySchedules.get(peerId);
            if (retry != null && now - retry.failedAtNanos() < TimeUnit.MILLISECONDS.toNanos(retry.delayMs())) {
                logger.debug("[CLPR-SYNC-OUTBOUND] peer waiting for retry delay peer={}", peerId);
                continue;
            }
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
            // Every tick repeats this while retry delays or circuit breakers defer the next attempt.
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] no candidate peer is ready for sync channel={} candidates={}",
                    channelId,
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

    /**
     * Returns the reputation tracker for a peer endpoint, creating one if needed.
     */
    @NonNull
    PeerReputation getReputation(@NonNull final String peerEndpointId) {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        return peerReputations.computeIfAbsent(
                peerEndpointId, id -> new PeerReputation(Duration.ofSeconds(clprConfig.reputationDecaySeconds())));
    }

    /** A bounded delay measured from the last failure using monotonic time. */
    private record RetrySchedule(long delayMs, long failedAtNanos) {}
}
