// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.NETWORK;

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
import com.swirlds.platform.gossip.sync.config.SyncConfig;
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
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Internal interface for encompassing piece of code writing bytes to network stream
 */
@FunctionalInterface
interface StreamWriter {
    void write(SyncOutputStream syncOutputStream) throws IOException;
}

/**
 * Message based implementation of gossip; currently supporting sync and simplistic chatter
 */
public class RpcPeerProtocol implements PeerProtocol, GossipRpcSender {

    private static final Logger logger = LogManager.getLogger(RpcPeerProtocol.class);

    /**
     * Send in place of batch size, to indicate that we are switching the protocol to something else
     */
    private static final short END_OF_CONVERSATION = -1;

    private static final int SYNC_DATA = 1;
    private static final int KNOWN_TIPS = 2;
    private static final int EVENT = 3;
    private static final int EVENTS_FINISHED = 4;
    private static final int PING = 5;
    private static final int PING_REPLY = 6;

    /**
     * All pending messages to be sent; instead of just messages, it is holding references to lambdas writing to network
     * (in most cases, putting number of messages, type of message and serialized PBJ on the wire)
     */
    private final BlockingQueue<StreamWriter> outputQueue = new LinkedBlockingQueue<>();

    /**
     * State machine for sync process
     */
    private final SyncConversation syncConversation;

    /**
     * Handler of incoming messages, in current implementation same as {@link #syncConversation}
     */
    private final GossipRpcReceiver receiver;

    /**
     * Id of the remote node we are communicating with
     */
    private final NodeId peerId;

    /**
     * executes tasks in parallel
     */
    private final ParallelExecutor executor;

    /**
     * Indicator that gossip was halted by external force (most probably reconnect protocol trying to resync graph)
     */
    private final Supplier<Boolean> gossipHalted;

    /**
     * Current platform status
     */
    private final Supplier<PlatformStatus> platformStatus;

    /**
     * Manage permits for concurrent syncs
     */
    private final SyncPermitProvider permitProvider;

    /**
     * Metrics for reporting network statistics
     */
    private final NetworkMetrics networkMetrics;

    /**
     * Platform time, to avoid tying ourselfs to real wall clock (useful for simulation)
     */
    private final Time time;

    /**
     * Metrics for sync-related statistics
     */
    private final SyncMetrics syncMetrics;

    /**
     * Our running ancient mode. It is common for entire network, so we can get it from settings once and not transmit
     * between nodes
     */
    private final AncientMode ancientMode;

    /**
     * Marker bool to exit processing output queue early in case we need to give control back to other protocols
     */
    private volatile boolean processMessagesToSend = false;

    /**
     * Increasing counter for ping correlation id
     */
    private long pingId = 1;

    /**
     * Timestamp for each ping correlation id, so ping time can be measured after reply
     */
    private final ConcurrentMap<Long, GossipPing> sentPings = Maps.newConcurrentMap();

    /**
     * Last time ping was sent, to keep track to avoid spamming network with ping requests
     */
    private long lastPingTime;

    /**
     * At what time conversation was stopped, used to measure possible timeout
     */
    private long conversationFinishPending;

    /**
     * After what time exception should be thrown, if we have sent end of conversation marker, but other side has not
     * replied in kind
     */
    private final long maxWaitForConversationFinishMs;

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
     * @param networkMetrics network metrics to register data about communication traffic and latencies
     * @param time           the {@link Time} instance for the platformeturns the {@link Time} instance for the
     *                       platform
     * @param syncMetrics    metrics tracking syncing
     * @param syncConfig     sync configuration
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
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final SyncConfig syncConfig,
            @NonNull final AncientMode ancientMode) {
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
        this.maxWaitForConversationFinishMs = syncConfig.maxSyncTime().toMillis();
        this.ancientMode = ancientMode;
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
            syncMetrics.doNotSyncHalted();
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void runProtocol(@NonNull final Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {

        Objects.requireNonNull(connection);

        processMessagesToSend = true;
        conversationFinishPending = -1L;
        try {
            executor.doParallel(
                    () -> readMessages(connection), () -> writeMessages(connection), connection::disconnect);
        } catch (final ParallelExecutionException e) {
            logger.error(NETWORK.getMarker(), "Failure during communication with node {}", peerId, e);
        } finally {
            permitProvider.release();
        }
        // later we will loop here, for now just exit the protocol
    }

    /**
     * Write all the messages pending in queue, until: - gossip is halted (due to pending reconnect on another
     * connection) - permits indicate that system is unhealthy (due to backpressure) - we have just detected that this
     * connection is falling behind
     *
     * @param connection connection over which data should be sent
     */
    private void writeMessages(@NonNull final Connection connection) throws IOException {

        Objects.requireNonNull(connection);

        final SyncOutputStream output = connection.getDos();

        while (shouldContinueSendingMessages()) {
            syncConversation.possiblyStartSync();
            syncMetrics.rpcQueueSize(outputQueue.size());
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
            if (outputQueue.isEmpty()) {
                // otherwise we will keep pushing messages to output, and they will get autoflushed, or we will
                // reach the end of the queue and do explicit flush
                output.flush();
            }
        }
        output.writeShort(END_OF_CONVERSATION);
        output.flush();
    }

    /**
     * Why 3 different rules for exiting?
     * <p>
     * <b>processMessagesToSend</b> means remote side said to us they want to end conversation and we should behave
     * <p>
     * <b>gossipHalted</b> means there is reconnect happening very soon, so we need to exit ASAP and free the permits and
     * connection
     * <p>
     * <b>permitProvider health</b> indicates that system is overloaded and we are getting backpressure; we need to give up on
     * spamming network and/or reading new messages and let things settle down
     *
     * @return true if sending messages loop should continue
     */
    private boolean shouldContinueSendingMessages() {
        return processMessagesToSend && !gossipHalted.get() && permitProvider.isHealthy();
    }

    /**
     * Read incoming messages in the loop, until - remote side sends end of conversation marker - we are pending finish
     * of conversation and enough time has passed (1 minute currently)
     *
     * @param connection connection to read messages from
     * @throws IOException          on any kind of I/O error
     * @throws SyncTimeoutException if conversation finish has not happened in the allotted time
     */
    private void readMessages(@NonNull final Connection connection) throws IOException, SyncTimeoutException {

        final SyncInputStream input = connection.getDis();
        while (true) {

            // check if other side should be already sending us end of conversation marker; if it is the case
            // and they haven't for long enough, break the connection, they might be malicious
            if (conversationFinishPending > 0
                    && time.currentTimeMillis() - conversationFinishPending > maxWaitForConversationFinishMs) {
                throw new SyncTimeoutException(
                        Duration.ofNanos(time.currentTimeMillis() - conversationFinishPending),
                        Duration.ofMillis(maxWaitForConversationFinishMs));
            }

            final short incomingBatchSize = input.readShort();

            // if remote side said to us it is end of conversation, we need to honor that, as very next byte will
            // be already part of new protocol negotiation
            if (incomingBatchSize == END_OF_CONVERSATION) {
                break;
            }

            for (int i = 0; i < incomingBatchSize; i++) {
                try {
                    final int messageType = input.read();
                    switch (messageType) {
                        case SYNC_DATA:
                            final GossipSyncData gossipSyncData = input.readPbjRecord(GossipSyncData.PROTOBUF);
                            receiver.receiveSyncData(SyncData.fromProtobuf(gossipSyncData, ancientMode));
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
                            handleIncomingPing(input.readPbjRecord(GossipPing.PROTOBUF));
                            break;
                        case PING_REPLY:
                            final GossipPing pingReply = input.readPbjRecord(GossipPing.PROTOBUF);
                            handleIncomingPingReply(pingReply);
                            break;
                    }
                } catch (final Exception e) {
                    logger.error(NETWORK.getMarker(), "Error reading messages", e);
                }
            }
        }
        processMessagesToSend = false;
    }

    private void handleIncomingPingReply(final GossipPing pingReply) {
        final GossipPing original = sentPings.remove(pingReply.correlationId());
        if (original == null) {
            logger.error(
                    NETWORK.getMarker(),
                    "Received unexpected gossip ping reply from peer {} for correlation id {}",
                    peerId,
                    pingReply.correlationId());
        } else {
            // don't trust remote timestamp for measuring ping
            logger.debug(NETWORK.getMarker(), "Ping {}", time.currentTimeMillis() - original.timestamp());
            networkMetrics.recordPingTime(peerId, (time.currentTimeMillis() - original.timestamp()) * 1_000_000);
        }
    }

    private void handleIncomingPing(final GossipPing ping) {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
            out.write(PING_REPLY);
            final GossipPing reply = new GossipPing(time.currentTimeMillis(), ping.correlationId());
            out.writePbjRecord(reply, GossipPing.PROTOBUF);
        });
    }

    private void possiblySendPing(SyncOutputStream output) throws IOException {
        final long timestamp = time.currentTimeMillis();
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
