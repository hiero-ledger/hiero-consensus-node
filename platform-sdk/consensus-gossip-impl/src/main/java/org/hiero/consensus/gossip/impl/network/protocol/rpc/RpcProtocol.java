// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.FREEZE;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.GossipController;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuard;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncGuardFactory;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncPermitProvider;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.RpcPeerHandler;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowgraphSynchronizer;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.gossip.impl.network.NetworkMetrics;
import org.hiero.consensus.gossip.impl.network.NetworkUtils;
import org.hiero.consensus.gossip.impl.network.protocol.PeerProtocol;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

/**
 * Implementation of a factory for rpc protocol, encompassing new sync and broadcast atm
 */
public class RpcProtocol implements Protocol, GossipController {

    private static final Logger logger = LogManager.getLogger(RpcProtocol.class);

    private final CachedPoolParallelExecutor executor;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private final NetworkMetrics networkMetrics;
    private final Time time;
    private final SyncMetrics syncMetrics;
    private final SyncConfig syncConfig;
    private final BroadcastConfig broadcastConfig;
    private final ShadowgraphSynchronizer synchronizer;
    private final SyncPermitProvider permitProvider;
    private final AtomicBoolean gossipHalted = new AtomicBoolean(false);
    private final IntakeEventCounter intakeEventCounter;

    /**
     * Our own node id
     */
    private final NodeId selfId;

    /**
     * Control for making sure that in case of limited amount of concurrent syncs we are not synchronizing with the same
     * peers over and over.
     */
    private final SyncGuard syncGuard;

    private final FallenBehindMonitor fallenBehindMonitor;
    private final Consumer<PlatformEvent> receivedEventHandler;

    private volatile boolean started;

    /**
     * List of all started sync exchanges with remote peers
     */
    private final List<RpcPeerHandler> allRpcPeers = new CopyOnWriteArrayList<>();

    /**
     * Constructs a new sync protocol
     *
     * @param configuration the platform configuration
     * @param metrics  the platform metrics
     * @param time source of time
     * @param synchronizer the shadow graph synchronizer, responsible for actually doing the sync
     * @param executor  executor to run read/write threads
     * @param intakeEventCounter keeps track of how many events have been received from each peerr
     * @param rosterSize estimated roster size
     * @param networkMetrics network metrics to register data about communication traffic and latencies
     * @param syncMetrics metrics tracking syncing platform
     * @param selfId id of the current node
     * @param fallenBehindMonitor shared monitoring of our event window falling behind peers
     * @param receivedEventHandler events that are received are passed here
     */
    public RpcProtocol(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ShadowgraphSynchronizer synchronizer,
            @NonNull final CachedPoolParallelExecutor executor,
            @NonNull final IntakeEventCounter intakeEventCounter,
            final int rosterSize,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final NodeId selfId,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler) {

        this.synchronizer = synchronizer;
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.syncConfig = configuration.getConfigData(SyncConfig.class);
        this.broadcastConfig = configuration.getConfigData(BroadcastConfig.class);
        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = rosterSize - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        this.permitProvider = new SyncPermitProvider(configuration, metrics, time, permitCount);
        this.executor = Objects.requireNonNull(executor);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.time = Objects.requireNonNull(time);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.selfId = selfId;

        this.syncGuard = SyncGuardFactory.create(
                syncConfig.fairMaxConcurrentSyncs(), syncConfig.fairMinimalRoundRobinSize(), rosterSize);
        this.fallenBehindMonitor = fallenBehindMonitor;
        this.receivedEventHandler = receivedEventHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        final RpcPeerProtocol peerProtocol = new RpcPeerProtocol(
                peerId,
                executor,
                gossipHalted::get,
                platformStatus::get,
                permitProvider,
                networkMetrics,
                time,
                syncMetrics,
                syncConfig,
                broadcastConfig,
                NetworkUtils::handleNetworkException);

        final RpcPeerHandler handler = new RpcPeerHandler(
                synchronizer,
                peerProtocol,
                selfId,
                peerId,
                syncMetrics,
                time,
                intakeEventCounter,
                receivedEventHandler,
                syncGuard,
                fallenBehindMonitor,
                syncConfig,
                broadcastConfig);

        peerProtocol.setRpcPeerHandler(handler);
        allRpcPeers.add(handler);
        return peerProtocol;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        platformStatus.set(status);
    }

    /**
     * Handle new event fully processed by the event intake. In this case used to optionally broadcast self events to
     * peer directly, skipping sync process
     *
     * @param platformEvent event to be processed
     */
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        // broadcast event to other nodes as part of simplistic broadcast
        if (broadcastConfig.enableBroadcast() && selfId.equals(platformEvent.getCreatorId())) {
            final GossipEvent gossipEvent = platformEvent.getGossipEvent();
            allRpcPeers.forEach(rpcPeer -> rpcPeer.broadcastEvent(gossipEvent));
            syncMetrics.broadcastEventSent();
        }
    }

    /**
     * Start gossiping
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("Gossip already started");
        }
        started = true;
        synchronizer.start();
        this.executor.start();
    }

    /**
     * Stop gossiping. This method is not fully working. It stops some threads, but leaves others running In particular,
     * you cannot call {@link #start()} () after calling stop (use {@link #pause()}{@link #resume()} as needed)
     */
    public void stop() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        logger.info(FREEZE.getMarker(), "Gossip frozen, reason: stopping gossip");
        gossipHalted.set(true);
        // wait for all existing syncs to stop. no new ones will be started, since gossip has been halted, and
        // we've fallen behind
        permitProvider.waitForAllPermitsToBeReleased();
        synchronizer.stop();
        this.executor.stop();
    }

    /**
     * Set total number of permits to previous number + passed difference
     *
     * @param permitsDifference positive to add permits, negative to remove permits
     */
    public void adjustTotalPermits(final int permitsDifference) {
        permitProvider.adjustTotalPermits(permitsDifference);
    }

    /**
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    public void pause() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        gossipHalted.set(true);
        permitProvider.waitForAllPermitsToBeReleased();
    }

    /**
     * Resume gossiping. Undoes the effect of {@link #pause()}. Should be called exactly once after each call to
     * {@link #pause()}.
     */
    public void resume() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        intakeEventCounter.reset();
        gossipHalted.set(false);

        // Revoke all permits when we begin gossiping again. Presumably we are behind the pack,
        // and so we want to avoid talking to too many peers at once until we've had a chance
        // to properly catch up.
        permitProvider.revokeAll();
    }

    /**
     * Report the health of the system
     *
     * @param duration duration that the system has been in an unhealthy state
     */
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        permitProvider.reportUnhealthyDuration(duration);
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    public void clear() {
        synchronizer.clear();
    }
}
