// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.shadowgraph.GossipRpcShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.protocol.AbstractSyncProtocol;
import com.swirlds.platform.network.protocol.PeerProtocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

public class RpcProtocol extends AbstractSyncProtocol<GossipRpcShadowgraphSynchronizer> {

    private final NodeId selfId;
    private final CachedPoolParallelExecutor executor;
    private final IntakeEventCounter intakeEventCounter;
    private final AtomicBoolean gossipHalted = new AtomicBoolean(false);

    protected RpcProtocol(
            @NonNull final GossipRpcShadowgraphSynchronizer synchronizer,
            @NonNull final NodeId selfId,
            @NonNull final CachedPoolParallelExecutor executor,
            @NonNull final IntakeEventCounter intakeEventCounter) {
        super(synchronizer);
        this.selfId = selfId;
        this.executor = executor;
        this.intakeEventCounter = intakeEventCounter;
    }

    /**
     * @param platformContext      the platform context
     * @param fallenBehindManager  tracks if we have fallen behind
     * @param receivedEventHandler output wiring to call when event is received from neighbour
     * @param intakeEventCounter   keeps track of how many events have been received from each peer
     * @param threadManager        the thread manager
     * @param rosterSize           estimated roster size
     * @param selfId               id of current node
     */
    public static RpcProtocol create(
            @NonNull final PlatformContext platformContext,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final ThreadManager threadManager,
            final int rosterSize,
            @NonNull final NodeId selfId) {

        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics());
        final Shadowgraph shadowgraph = new Shadowgraph(platformContext, rosterSize, intakeEventCounter);

        final GossipRpcShadowgraphSynchronizer synchronizer = new GossipRpcShadowgraphSynchronizer(
                platformContext,
                shadowgraph,
                rosterSize,
                syncMetrics,
                receivedEventHandler,
                fallenBehindManager,
                intakeEventCounter,
                selfId);

        return new RpcProtocol(
                synchronizer,
                selfId,
                new CachedPoolParallelExecutor(threadManager, "node-rpc-sync"),
                intakeEventCounter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new RpcPeerProtocol(selfId, peerId, synchronizer, executor, gossipHalted::get);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void adjustTotalPermits(final int permitsDifference) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        this.executor.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        this.executor.stop();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        synchronizer.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(final Duration duration) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void pause() {
        gossipHalted.set(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() {
        gossipHalted.set(false);
        intakeEventCounter.reset();
    }
}
