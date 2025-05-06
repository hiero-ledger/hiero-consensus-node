// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.google.common.collect.Lists;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.message.GossipKnownTips;
import com.hedera.hapi.platform.message.GossipSyncData;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.gossip.shadowgraph.GossipRpcShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.GossipRpcShadowgraphSynchronizer.SyncConversation;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.PeerProtocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

@FunctionalInterface
interface StreamWriter {
    void write(SyncOutputStream syncOutputStream) throws IOException;
}

/**
 * Message based implementation of gossip; currently supporting sync and simplistic chatter
 */
public class RpcPeerProtocol implements PeerProtocol, GossipRpcSender {

    private static final Logger logger = LogManager.getLogger(RpcPeerProtocol.class);

    private static final int SYNC_DATA = 1;
    private static final int KNOWN_TIPS = 2;
    private static final int FALLEN_BEHIND = 3;
    private static final int EVENT = 4;
    private static final int EVENTS_FINISHED = 5;

    private final Queue<StreamWriter> outputQueue = new ConcurrentLinkedQueue<>();

    private final SyncConversation syncConversation;
    private final GossipRpcReceiver receiver;
    private final NodeId peerId;

    /**
     * executes tasks in parallel
     */
    private final ParallelExecutor executor;

    private final Supplier<Boolean> gossipHalted;

    /**
     * Constructs a new rpc protocol
     *
     * @param selfId       the id of this node
     * @param peerId       the id of the peer being synced with in this protocol
     * @param synchronizer the shadow graph synchronizer, responsible for actually doing the sync
     * @param executor     executor to run parallel network tasks
     * @param gossipHalted
     */
    public RpcPeerProtocol(
            @NonNull final NodeId selfId,
            @NonNull final NodeId peerId,
            @NonNull final GossipRpcShadowgraphSynchronizer synchronizer,
            @NonNull final ParallelExecutor executor,
            @NonNull final Supplier<Boolean> gossipHalted) {
        this.syncConversation = synchronizer.synchronize(this, selfId, peerId);
        this.receiver = syncConversation;
        this.executor = executor;
        this.peerId = peerId;
        this.gossipHalted = gossipHalted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldInitiate() {
        return !gossipHalted.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAccept() {
        return !gossipHalted.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void acceptFailed() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void initiateFailed() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acceptOnSimultaneousInitiate() {
        return true;
    }

    @Override
    public void runProtocol(@NonNull final Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {

        syncConversation.possiblyStartSync();
        try {
            executor.doParallel(() -> readMessages(connection), () -> writeMessages(connection), () -> {});
        } catch (final ParallelExecutionException e) {
            logger.error("Failure during communication with node {}", peerId, e);
        }
        // later we will loop here, for now just exit the protocol
    }

    private void writeMessages(@NonNull final Connection connection) throws IOException {
        final SyncOutputStream output = connection.getDos();

        while (!outputQueue.isEmpty()) {
            outputQueue.poll().write(output);
        }
        output.writeShort(0);
        output.flush();

        // TODO: monitor queue size
        //
        //        if (!outputQueue.isEmpty()) {
        //            outputQueue.poll().write(output);
        //        } else {
        //            output.writeShort(0);
        //        }
        //        output.flush();
    }

    private void readMessages(@NonNull final Connection connection) throws IOException {

        final SyncInputStream input = connection.getDis();
        while (true) {
            final short incomingBatchSize = input.readShort();
            if (incomingBatchSize == 0) {
                break;
            }
            for (int i = 0; i < incomingBatchSize; i++) {
                try {
                    final int messageType = input.read();
                    switch (messageType) {
                        case SYNC_DATA:
                            final GossipSyncData gossipSyncData = input.readPbjRecord(GossipSyncData.PROTOBUF);
                            receiver.receiveSyncData(SyncData.fromProtobuf(gossipSyncData));
                            break;
                        case KNOWN_TIPS:
                            final GossipKnownTips knownTips = input.readPbjRecord(GossipKnownTips.PROTOBUF);
                            receiver.receiveTips(knownTips.knownTips());
                            break;
                        case FALLEN_BEHIND:
                            receiver.receiveFallenBehind();
                            break;
                        case EVENT:
                            receiver.receiveEvents(
                                    Collections.singletonList(input.readPbjRecord(GossipEvent.PROTOBUF)));
                            break;
                        case EVENTS_FINISHED:
                            receiver.receiveEventsFinished();
                            break;
                    }
                } catch (final Exception e) {
                    logger.error(NETWORK.getMarker(), "Error reading messages", e);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendSyncData(@NonNull final SyncData syncMessage) {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
            out.write(SYNC_DATA);
            out.writePbjRecord(syncMessage.toProtobuf(), GossipSyncData.PROTOBUF);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendFallenBehind() {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
            out.write(FALLEN_BEHIND);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendTips(@NonNull final List<Boolean> tips) {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
            out.write(KNOWN_TIPS);
            out.writePbjRecord(GossipKnownTips.newBuilder().knownTips(tips).build(), GossipKnownTips.PROTOBUF);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendEvents(@NonNull final List<GossipEvent> gossipEvents) {
        outputQueue.add(out -> {
            final List<List<GossipEvent>> batches = Lists.partition(gossipEvents, 1024 * 1024);
            {
                for (final List<GossipEvent> batch : batches) {
                    out.writeShort(batch.size());
                    for (final GossipEvent gossipEvent : batch) {
                        out.write(EVENT);
                        out.writePbjRecord(gossipEvent, GossipEvent.PROTOBUF);
                    }
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> sendEndOfEvents() {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        outputQueue.add(out -> {
            out.writeShort(1);
            out.write(EVENTS_FINISHED);
            future.complete(null);
        });
        return future;
    }
}
