// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.NETWORK;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.message.GossipKnownTips;
import com.hedera.hapi.platform.message.GossipPing;
import com.hedera.hapi.platform.message.GossipSyncData;
import com.swirlds.base.time.Time;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.gossip.shadowgraph.GossipRpcShadowgraphSynchronizer;
import com.swirlds.platform.gossip.shadowgraph.GossipRpcShadowgraphSynchronizer.SyncConversation;
import com.swirlds.platform.gossip.shadowgraph.SyncTimeoutException;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.gossip.sync.protocol.SyncStatusChecker;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.PeerProtocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

@FunctionalInterface
interface StreamWriter {
    void write(SyncOutputStream syncOutputStream) throws IOException;
}

/**
 * Message based implementation of gossip; currently supporting sync and simplistic chatter
 */
public class RpcPeerProtocol implements PeerProtocol, GossipRpcSender {

    private static final Logger logger = LogManager.getLogger(RpcPeerProtocol.class);

    private static final short END_OF_CONVERSATION = -1;

    private static final int SYNC_DATA = 1;
    private static final int KNOWN_TIPS = 2;
    private static final int EVENT = 3;
    private static final int EVENTS_FINISHED = 4;
    private static final int PING = 5;
    private static final int PING_REPLY = 6;

    private final BlockingQueue<StreamWriter> outputQueue = new LinkedBlockingQueue<>();

    private final SyncConversation syncConversation;
    private final GossipRpcReceiver receiver;
    private final NodeId peerId;

    /**
     * executes tasks in parallel
     */
    private final ParallelExecutor executor;

    private final Supplier<Boolean> gossipHalted;
    private final Supplier<PlatformStatus> platformStatus;
    private final SyncPermitProvider permitProvider;
    private final NetworkMetrics networkMetrics;
    private final Time time;
    private final SyncMetrics syncMetrics;
    private volatile boolean processMessagesToSend = false;
    private long pingId = 1;
    private ConcurrentMap<Long, GossipPing> sentPings = Maps.newConcurrentMap();
    private long lastPingTime;
    private long conversationFinishPending;
    private final long maxWaitForConversationFinishMs = 60 * 1000;

    /**
     * Constructs a new rpc protocol
     *
     * @param selfId         the id of this node
     * @param peerId         the id of the peer being synced with in this protocol
     * @param synchronizer   the shadow graph synchronizer, responsible for actually doing the sync
     * @param executor       executor to run parallel network tasks
     * @param gossipHalted   returns true if gossip is halted, false otherwise
     * @param platformStatus provides the current platform status
     * @param permitProvider provides permits to sync
     * @param networkMetrics
     * @param time
     * @param syncMetrics
     */
    public RpcPeerProtocol(
            @NonNull final NodeId selfId,
            @NonNull final NodeId peerId,
            @NonNull final GossipRpcShadowgraphSynchronizer synchronizer,
            @NonNull final ParallelExecutor executor,
            @NonNull final Supplier<Boolean> gossipHalted,
            @NonNull final Supplier<PlatformStatus> platformStatus,
            @NonNull final SyncPermitProvider permitProvider,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final Time time,
            @NonNull final SyncMetrics syncMetrics) {
        this.executor = Objects.requireNonNull(executor);
        this.peerId = Objects.requireNonNull(peerId);
        this.gossipHalted = Objects.requireNonNull(gossipHalted);
        this.platformStatus = Objects.requireNonNull(platformStatus);
        this.permitProvider = Objects.requireNonNull(permitProvider);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.time = Objects.requireNonNull(time);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.syncConversation = synchronizer.synchronize(this, selfId, peerId);
        this.receiver = syncConversation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldInitiate() {
        return shouldSync();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAccept() {
        return shouldSync();
    }

    private boolean shouldSync() {
        if (gossipHalted.get()) {
            return false;
        }

        if (!SyncStatusChecker.doesStatusPermitSync(platformStatus.get())) {
            syncMetrics.doNotSyncPlatformStatus();
            return false;
        }

        if (!permitProvider.acquire()) {
            syncMetrics.doNotSyncNoPermits();
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void acceptFailed() {
        permitProvider.release();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initiateFailed() {
        permitProvider.release();
    }

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

        Objects.requireNonNull(connection);

        processMessagesToSend = true;
        conversationFinishPending = -1L;
        try {
            executor.doParallel(() -> readMessages(connection), () -> writeMessages(connection), () -> {});
        } catch (final ParallelExecutionException e) {
            logger.error(NETWORK.getMarker(), "Failure during communication with node {}", peerId, e);
        } finally {
            permitProvider.release();
        }
        // later we will loop here, for now just exit the protocol
    }

    private void writeMessages(@NonNull final Connection connection) throws IOException {

        Objects.requireNonNull(connection);

        final SyncOutputStream output = connection.getDos();

        while (processMessagesToSend && !gossipHalted.get() && permitProvider.isHealthy()) {
            syncConversation.possiblyStartSync();
            final StreamWriter message;
            try {
                message = outputQueue.poll(5, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                processMessagesToSend = false;
                logger.warn("Interrupted while waiting for message", e);
                break;
            }
            if (message == null) {
                possiblySendPing(output);
            } else {
                message.write(output);
            }
            output.flush();
        }
        output.writeShort(END_OF_CONVERSATION);
        output.flush();

        // TODO: monitor queue size

    }

    private void readMessages(@NonNull final Connection connection) throws IOException, SyncTimeoutException {

        final SyncInputStream input = connection.getDis();
        while (true) {
            if (conversationFinishPending > 0
                    && time.currentTimeMillis() - conversationFinishPending > maxWaitForConversationFinishMs) {
                throw new SyncTimeoutException(
                        Duration.ofNanos(time.currentTimeMillis() - conversationFinishPending),
                        Duration.ofMillis(maxWaitForConversationFinishMs));
            }
            final short incomingBatchSize = input.readShort();
            if (incomingBatchSize == END_OF_CONVERSATION) {
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
                        case EVENT:
                            receiver.receiveEvents(
                                    Collections.singletonList(input.readPbjRecord(GossipEvent.PROTOBUF)));
                            break;
                        case EVENTS_FINISHED:
                            receiver.receiveEventsFinished();
                            break;
                        case PING:
                            final GossipPing ping = input.readPbjRecord(GossipPing.PROTOBUF);
                            outputQueue.add(out -> {
                                out.writeShort(1); // single message
                                out.write(PING_REPLY);
                                final GossipPing reply = new GossipPing(time.currentTimeMillis(), ping.correlationId());
                                out.writePbjRecord(reply, GossipPing.PROTOBUF);
                            });
                            break;
                        case PING_REPLY:
                            final GossipPing pingReply = input.readPbjRecord(GossipPing.PROTOBUF);
                            final GossipPing original = sentPings.remove(pingReply.correlationId());
                            if (original == null) {
                                logger.error(
                                        "Received unexpected gossip ping reply from peer {} for correlation id {}",
                                        peerId,
                                        pingReply.correlationId());
                            } else {
                                // don't trust remote timestamp for measuring ping
                                logger.info(
                                        RECONNECT.getMarker(),
                                        "Ping {}",
                                        time.currentTimeMillis() - original.timestamp());
                                networkMetrics.recordPingTime(
                                        peerId, (time.currentTimeMillis() - original.timestamp()) * 1_000_000);
                            }
                            break;
                    }
                } catch (final Exception e) {
                    logger.error(NETWORK.getMarker(), "Error reading messages", e);
                }
            }
        }
        processMessagesToSend = false;
    }

    private void possiblySendPing(SyncOutputStream output) throws IOException {
        var timestamp = time.currentTimeMillis();
        if ((timestamp - lastPingTime) < 1000) {
            return;
        }
        this.lastPingTime = timestamp;
        output.writeShort(1);
        output.write(PING);
        final GossipPing ping = new GossipPing(timestamp, pingId++);
        sentPings.put(ping.correlationId(), ping);
        output.writePbjRecord(ping, GossipPing.PROTOBUF);
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
                    if (!batch.isEmpty()) {
                        out.writeShort(batch.size());
                        for (final GossipEvent gossipEvent : batch) {
                            out.write(EVENT);
                            out.writePbjRecord(gossipEvent, GossipEvent.PROTOBUF);
                        }
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void breakConversation() {
        this.conversationFinishPending = time.currentTimeMillis();
        this.processMessagesToSend = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cleanup() {
        this.syncConversation.cleanup();
    }
}
