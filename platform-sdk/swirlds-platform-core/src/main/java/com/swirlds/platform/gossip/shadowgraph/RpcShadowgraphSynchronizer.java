// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * The goal of the GossipRpcShadowgraphSynchronizer is to compare graphs with a remote node, and update them so both
 * sides have the same events in the graph. This process is called a sync.
 * <p>
 * This class is designed to use message based protocol (as a precursor to RPC communication) instead of stealing entire
 * socket (unlike {@link ShadowgraphSynchronizer}
 */
public class RpcShadowgraphSynchronizer extends AbstractShadowgraphSynchronizer {

    private static final Logger logger = LogManager.getLogger(RpcShadowgraphSynchronizer.class);

    /**
     * List of all started sync exchanges with remote peers
     */
    private final List<RpcPeerHandler> allRpcPeers = new CopyOnWriteArrayList<>();

    /**
     * Our own node id
     */
    private final NodeId selfId;

    /**
     * How long should we wait between sync attempts
     */
    private final Duration sleepAfterSync;

    /**
     * Do we use broadcast to communicate self events
     */
    private final boolean broadcastEnabled;

    /**
     * Constructs a new ShadowgraphSynchronizer.
     *
     * @param platformContext      the platform context
     * @param numberOfNodes        number of nodes in the network
     * @param syncMetrics          metrics for sync
     * @param receivedEventHandler events that are received are passed here
     * @param fallenBehindManager  tracks if we have fallen behind
     * @param intakeEventCounter   used for tracking events in the intake pipeline per peer
     * @param selfId               id of current node
     */
    public RpcShadowgraphSynchronizer(
            @NonNull final PlatformContext platformContext,
            final int numberOfNodes,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final NodeId selfId) {

        super(
                platformContext,
                new Shadowgraph(platformContext, numberOfNodes, intakeEventCounter),
                numberOfNodes,
                syncMetrics,
                receivedEventHandler,
                fallenBehindManager,
                intakeEventCounter);
        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        this.selfId = selfId;
        this.sleepAfterSync = syncConfig.rpcSleepAfterSync();
        this.broadcastEnabled = syncConfig.broadcast();

    }

    /**
     * Create new state class for the RPC peer
     *
     * @param sender      endpoint for sending messages to peer endpoint asynchronously
     * @param otherNodeId id of the remote node
     * @return rpc peer state object
     */
    public RpcPeerHandler createPeerHandler(@NonNull final GossipRpcSender sender, @NonNull final NodeId otherNodeId) {
        final RpcPeerHandler rpcPeerState = new RpcPeerHandler(
                this, sender, selfId, otherNodeId, sleepAfterSync, syncMetrics, time, intakeEventCounter, eventHandler);
        allRpcPeers.add(rpcPeerState);
        return rpcPeerState;
    }

    /**
     * Called when given handler is being destroyed due to connection collapsing or other similar event.
     *
     * @param rpcPeerHandler handler which should be removed from internal structures
     */
    public void deregisterPeerHandler(final RpcPeerHandler rpcPeerHandler) {
        this.allRpcPeers.remove(rpcPeerHandler);
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
}
