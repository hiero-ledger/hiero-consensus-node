// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import static com.swirlds.logging.legacy.LogMarker.FREEZE;

import com.google.common.annotations.VisibleForTesting;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.platform.gossip.GossipController;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.gossip.sync.protocol.SyncPeerProtocol;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implementation of a factory for sync protocol
 */
public class SyncProtocol implements Protocol, GossipController {

    private static final Logger logger = LogManager.getLogger(SyncProtocol.class);

    private final PlatformContext platformContext;
    private final ShadowgraphSynchronizer synchronizer;
    private final FallenBehindManager fallenBehindManager;
    private final SyncPermitProvider permitProvider;
    private final IntakeEventCounter intakeEventCounter;
    private final AtomicBoolean gossipHalted = new AtomicBoolean(false);
    private final Duration sleepAfterSync;
    private final SyncMetrics syncMetrics;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private volatile boolean started;

    /**
     * Constructs a new sync protocol
     *
     * @param platformContext     the platform context
     * @param synchronizer        the shadow graph synchronizer, responsible for actually doing the sync
     * @param fallenBehindManager manager to determine whether this node has fallen behind
     * @param intakeEventCounter  keeps track of how many events have been received from each peer
     * @param sleepAfterSync      the amount of time to sleep after a sync
     * @param syncMetrics         metrics tracking syncing
     */
    public SyncProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ShadowgraphSynchronizer synchronizer,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Duration sleepAfterSync,
            @NonNull final SyncMetrics syncMetrics,
            final int rosterSize) {

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = rosterSize - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        this.permitProvider = new SyncPermitProvider(platformContext, permitCount);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.synchronizer = Objects.requireNonNull(synchronizer);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
    }

    /**
     * Utility method for creating SyncProtocol from shared state, while staying compatible with pre-refactor code
     *
     * @param platformContext      the platform context
     * @param fallenBehindManager  tracks if we have fallen behind
     * @param receivedEventHandler output wiring to call when event is received from neighbour
     * @param intakeEventCounter   keeps track of how many events have been received from each peer
     * @param threadManager        the thread manager
     * @param rosterSize           estimated roster size
     * @return constructed SyncProtocol
     */
    public static SyncProtocol create(
            @NonNull final PlatformContext platformContext,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final ThreadManager threadManager,
            final int rosterSize) {

        final CachedPoolParallelExecutor shadowgraphExecutor =
                new CachedPoolParallelExecutor(threadManager, "node-sync");

        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics());

        final Shadowgraph shadowgraph = new Shadowgraph(platformContext, rosterSize, intakeEventCounter);

        final ShadowgraphSynchronizer syncShadowgraphSynchronizer = new ShadowgraphSynchronizer(
                platformContext,
                shadowgraph,
                rosterSize,
                syncMetrics,
                receivedEventHandler,
                fallenBehindManager,
                intakeEventCounter,
                shadowgraphExecutor);

        return new SyncProtocol(
                platformContext,
                syncShadowgraphSynchronizer,
                fallenBehindManager,
                intakeEventCounter,
                Duration.ZERO,
                syncMetrics,
                rosterSize);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SyncPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new SyncPeerProtocol(
                platformContext,
                Objects.requireNonNull(peerId),
                synchronizer,
                fallenBehindManager,
                permitProvider,
                intakeEventCounter,
                gossipHalted::get,
                sleepAfterSync,
                syncMetrics,
                platformStatus::get);
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    public void clear() {
        synchronizer.clear();
    }

    /**
     * Events sent here should be gossiped to the network
     *
     * @param platformEvent event to be sent outside
     */
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        synchronizer.addEvent(platformEvent);
    }

    /**
     * Updates the current event window (mostly ancient thresholds)
     *
     * @param eventWindow new event window to apply
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        synchronizer.updateEventWindow(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        platformStatus.set(status);
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
     * Set total number of permits to previous number + passed difference
     *
     * @param permitsDifference positive to add permits, negative to remove permits
     */
    public void adjustTotalPermits(final int permitsDifference) {
        permitProvider.adjustTotalPermits(permitsDifference);
    }

    /**
     * Used by legacy testing to check available permits. Package-private to avoid polluting public space
     *
     * @return internal permit provider
     */
    @VisibleForTesting
    SyncPermitProvider getPermitProvider() {
        return permitProvider;
    }
}
