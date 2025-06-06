// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.google.common.annotations.VisibleForTesting;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.protocol.SyncPeerProtocol;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implementation of a factory for sync protocol
 */
public class SyncProtocol extends AbstractSyncProtocol<ShadowgraphSynchronizer> {

    private static final Logger logger = LogManager.getLogger(SyncProtocol.class);

    private final PlatformContext platformContext;
    private final FallenBehindManager fallenBehindManager;
    private final Duration sleepAfterSync;
    private final SyncMetrics syncMetrics;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);

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

        super(synchronizer, platformContext, rosterSize, intakeEventCounter);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
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

        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics(), platformContext.getTime());

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
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        platformStatus.set(status);
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
