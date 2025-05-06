// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.shadowgraph.GossipRpcShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.RpcPeerHandler;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.protocol.AbstractSyncProtocol;
import com.swirlds.platform.network.protocol.PeerProtocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implementation of a factory for rpc protocol, encompassing new sync and broadcast atm
 */
public class RpcProtocol extends AbstractSyncProtocol<GossipRpcShadowgraphSynchronizer> {

    private final CachedPoolParallelExecutor executor;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private final NetworkMetrics networkMetrics;
    private final Time time;
    private final SyncMetrics syncMetrics;

    /**
     * Constructs a new sync protocol
     *
     * @param synchronizer       the shadow graph synchronizer, responsible for actually doing the sync
     * @param executor           executor to run read/write threads
     * @param intakeEventCounter keeps track of how many events have been received from each peerr
     * @param platformContext    the platform context
     * @param rosterSize         estimated roster size
     * @param networkMetrics     network metrics to register data about communication traffic and latencies
     * @param time               the {@link Time} instance for the platformeturns the {@link Time} instance for the
     * @param syncMetrics        metrics tracking syncing platform
     */
    protected RpcProtocol(
            @NonNull final GossipRpcShadowgraphSynchronizer synchronizer,
            @NonNull final CachedPoolParallelExecutor executor,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final PlatformContext platformContext,
            final int rosterSize,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final Time time,
            @NonNull final SyncMetrics syncMetrics) {
        super(synchronizer, platformContext, rosterSize, intakeEventCounter);
        this.executor = Objects.requireNonNull(executor);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.time = Objects.requireNonNull(time);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
    }

    /**
     * @param platformContext      the platform context
     * @param fallenBehindManager  tracks if we have fallen behind
     * @param receivedEventHandler output wiring to call when event is received from neighbour
     * @param intakeEventCounter   keeps track of how many events have been received from each peer
     * @param threadManager        the thread manager
     * @param rosterSize           estimated roster size
     * @param selfId               id of current node
     * @param networkMetrics       network metrics to register data about communication traffic and latencies
     */
    public static RpcProtocol create(
            @NonNull final PlatformContext platformContext,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final ThreadManager threadManager,
            final int rosterSize,
            @NonNull final NodeId selfId,
            @NonNull final NetworkMetrics networkMetrics) {

        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics(), platformContext.getTime());
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
                new CachedPoolParallelExecutor(threadManager, "node-rpc-sync"),
                intakeEventCounter,
                platformContext,
                rosterSize,
                networkMetrics,
                platformContext.getTime(),
                syncMetrics);
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
                syncConfig);
        final RpcPeerHandler handler = synchronizer.createPeerHandler(peerProtocol, peerId);
        peerProtocol.setRpcPeerHandler(handler);
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
    public void start() {
        super.start();
        this.executor.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        this.executor.stop();
        super.stop();
    }
}
