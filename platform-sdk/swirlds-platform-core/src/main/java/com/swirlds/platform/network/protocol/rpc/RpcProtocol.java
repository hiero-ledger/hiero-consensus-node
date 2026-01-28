// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import com.hedera.hapi.platform.event.GossipEvent;
import static com.swirlds.logging.legacy.LogMarker.FREEZE;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.GossipController;
import com.swirlds.platform.gossip.permits.SyncGuard;
import com.swirlds.platform.gossip.permits.SyncGuardFactory;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.RpcPeerHandler;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.protocol.PeerProtocol;
import com.swirlds.platform.network.protocol.Protocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.gossip.config.SyncConfig;
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
    private final ShadowgraphSynchronizer synchronizer;
    private final SyncPermitProvider permitProvider;
    private final AtomicBoolean gossipHalted = new AtomicBoolean(false);
    private final IntakeEventCounter intakeEventCounter;

    /**
     * Our own node id
     */
    private final NodeId selfId;

    /**
     * How long should we wait between sync attempts
     */
    private final Duration sleepAfterSync;

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
     * Is event broadcast enabled in settings
     */
    private final boolean broadcastEnabled;

    /**
     * Node id of current node
     */
    private final NodeId selfId;

    /**
     * Constructs a new sync protocol
     *
     * @param synchronizer         the shadow graph synchronizer, responsible for actually doing the sync
     * @param executor             executor to run read/write threads
     * @param intakeEventCounter   keeps track of how many events have been received from each peerr
     * @param platformContext      the platform context
     * @param rosterSize           estimated roster size
     * @param networkMetrics       network metrics to register data about communication traffic and latencies
     * @param time                 the {@link Time} instance for the platformeturns the {@link Time} instance for the
     * @param syncMetrics          metrics tracking syncing platform
     * @param selfId               id of the current node
     * @param fallenBehindMonitor  shared monitoring of our event window falling behind peers
     * @param receivedEventHandler events that are received are passed here
     */
    public RpcProtocol(
            @NonNull final ShadowgraphSynchronizer synchronizer,
            @NonNull final CachedPoolParallelExecutor executor,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final PlatformContext platformContext,
            final int rosterSize,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final Time time,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final NodeId selfId,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler) {

        this.synchronizer = synchronizer;
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        this.syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = rosterSize - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        this.sleepAfterSync = syncConfig.rpcSleepAfterSync();

        this.permitProvider = new SyncPermitProvider(platformContext, permitCount);
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
                NetworkUtils::handleNetworkException);

        final RpcPeerHandler handler = new RpcPeerHandler(
                synchronizer,
                peerProtocol,
                selfId,
                peerId,
                sleepAfterSync,
                syncMetrics,
                time,
                intakeEventCounter,
                receivedEventHandler,
                syncGuard,
                fallenBehindMonitor);

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
     * {@inheritDoc}
     */
    @Override
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        super.addEvent(platformEvent);

        // broadcast event to other nodes as part of simplistic broadcast
        if (broadcastEnabled && selfId.equals(platformEvent.getCreatorId())) {
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
