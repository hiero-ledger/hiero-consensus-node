// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.network.protocol.rpc.RpcMessageId.EVENT;
import static com.swirlds.platform.network.protocol.rpc.RpcMessageId.EVENTS_FINISHED;
import static com.swirlds.platform.network.protocol.rpc.RpcMessageId.KNOWN_TIPS;
import static com.swirlds.platform.network.protocol.rpc.RpcMessageId.PING;
import static com.swirlds.platform.network.protocol.rpc.RpcMessageId.PING_REPLY;
import static com.swirlds.platform.network.protocol.rpc.RpcMessageId.SYNC_DATA;

import com.google.common.collect.Lists;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.message.GossipKnownTips;
import com.hedera.hapi.platform.message.GossipPing;
import com.hedera.hapi.platform.message.GossipSyncData;
import com.swirlds.base.time.Time;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiver;
import com.swirlds.platform.gossip.rpc.GossipRpcSender;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.gossip.shadowgraph.RpcPeerHandler;
import com.swirlds.platform.gossip.shadowgraph.SyncPhase;
import com.swirlds.platform.gossip.shadowgraph.SyncTimeoutException;
import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.gossip.sync.protocol.SyncStatusChecker;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.protocol.PeerProtocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Message based implementation of gossip; currently supporting sync Responsible for communication with a single peer
 */
public class RpcPeerProtocol implements PeerProtocol, GossipRpcSender {

    private static final Logger logger = LogManager.getLogger(RpcPeerProtocol.class);

    /**
     * Send in place of batch size, to indicate that we are switching the protocol to something else
     */
    static final short END_OF_CONVERSATION = -1;

    /**
     * Maximum amount of events in a single message batch
     */
    private static final int EVENT_BATCH_SIZE = 512;

    /**
     * All pending messages to be sent; instead of just messages, it is holding references to lambdas writing to network
     * (in most cases, writing the number of messages, the type of message, then finally the serialized PBJ on the
     * wire)
     */
    private final BlockingQueue<StreamWriter> outputQueue = new LinkedBlockingQueue<>();

    /**
     * All incoming messages from remote node to be passed to dispatch thread
     */
    private final BlockingQueue<Runnable> inputQueue = new LinkedBlockingQueue<>();

    /**
     * Helper class to handle ping logic
     */
    private final RpcPingHandler pingHandler;

    private final boolean keepSendingEventsWhenUnhealthy;

    /** A duration between reporting full stack traces for socket exceptions. */
    private static final Duration SOCKET_EXCEPTION_DURATION = Duration.ofMinutes(1);

    private final RateLimiter exceptionRateLimiter;

    /**
     * State machine for rpc exchange process (mostly sync process)
     */
    private RpcPeerHandler rpcPeerHandler;

    /**
     * Handler of incoming messages, in current implementation same as {@link #rpcPeerHandler}
     */
    private GossipRpcReceiver receiver;

    /**
     * The id of the remote node we are communicating with
     */
    private final NodeId remotePeerId;

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
     * Platform time, to avoid tying ourselves to real wall clock (useful for simulation)
     */
    private final Time time;

    /**
     * Metrics for sync-related statistics
     */
    private final SyncMetrics syncMetrics;

    /**
     * How long to wait if nothing is happening on the write queue, to check for exit/ping handling
     */
    private final long idleWritePollTimeoutMs;

    /**
     * How long to wait if nothing is happening on dispatch queue, to check for possible periodic actions (current,
     * starting of synchronization, if it is not already in progress)
     */
    private final long idleDispatchPollTimeoutMs;

    /**
     * Marker bool to exit processing output queue early in case we need to give control back to other protocols
     */
    private volatile boolean processMessages = false;

    /**
     * At what time conversation was stopped, used to measure possible timeout
     */
    private volatile long conversationFinishPending;

    /**
     * After what time exception should be thrown, if we have sent end of conversation marker, but other side has not
     * replied in kind
     */
    private final long maxWaitForConversationFinishMs;

    /**
     * If we need to get out of RPC context, let's remember which sync phase we were in previously
     */
    private SyncPhase previousPhase = SyncPhase.IDLE;

    /**
     * Special marker to indicate that dispatch thread should exit its loop
     */
    private static final Runnable POISON_PILL =
            () -> logger.error(EXCEPTION.getMarker(), "Poison pill should never be executed");

    /**
     * Constructs a new rpc protocol
     *
     * @param peerId         the id of the peer being synced with in this protocol
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
            @NonNull final NodeId peerId,
            @NonNull final ParallelExecutor executor,
            @NonNull final Supplier<Boolean> gossipHalted,
            @NonNull final Supplier<PlatformStatus> platformStatus,
            @NonNull final SyncPermitProvider permitProvider,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final Time time,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final SyncConfig syncConfig) {
        this.executor = Objects.requireNonNull(executor);
        this.remotePeerId = Objects.requireNonNull(peerId);
        this.gossipHalted = Objects.requireNonNull(gossipHalted);
        this.platformStatus = Objects.requireNonNull(platformStatus);
        this.permitProvider = Objects.requireNonNull(permitProvider);
        this.time = Objects.requireNonNull(time);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.maxWaitForConversationFinishMs = syncConfig.maxSyncTime().toMillis();
        this.idleDispatchPollTimeoutMs = syncConfig.rpcIdleDispatchPollTimeout().toMillis();
        this.idleWritePollTimeoutMs = syncConfig.rpcIdleWritePollTimeout().toMillis();
        this.pingHandler = new RpcPingHandler(time, networkMetrics, remotePeerId, this);
        this.keepSendingEventsWhenUnhealthy = syncConfig.keepSendingEventsWhenUnhealthy();

        this.exceptionRateLimiter = new RateLimiter(time, SOCKET_EXCEPTION_DURATION);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldInitiate() {
        return shouldSwitchToRpc();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAccept() {
        return shouldSwitchToRpc();
    }

    private boolean shouldSwitchToRpc() {
        if (gossipHalted.get()) {
            syncMetrics.doNotSyncHalted();
            syncMetrics.reportSyncPhase(remotePeerId, SyncPhase.GOSSIP_HALTED);
            return false;
        }

        if (!SyncStatusChecker.doesStatusPermitSync(platformStatus.get())) {
            syncMetrics.doNotSyncPlatformStatus();
            syncMetrics.reportSyncPhase(remotePeerId, SyncPhase.PLATFORM_STATUS_PREVENTING_SYNC);
            return false;
        }

        if (!permitProvider.acquire()) {
            syncMetrics.reportSyncPhase(remotePeerId, SyncPhase.NO_PERMIT);
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

        processMessages = true;
        conversationFinishPending = -1L;
        syncMetrics.reportSyncPhase(remotePeerId, previousPhase);
        try {
            executor.doParallelWithHandler(
                    connection::disconnect,
                    (ThrowingRunnable) this::dispatchInputMessages,
                    () -> readMessages(connection),
                    () -> writeMessages(connection));
        } catch (final ParallelExecutionException e) {
            NetworkUtils.handleNetworkException(e, connection, exceptionRateLimiter);
        } finally {
            permitProvider.release();
            previousPhase = syncMetrics.reportSyncPhase(remotePeerId, SyncPhase.OUTSIDE_OF_RPC);
        }
        // later we will loop here, for now just exit the protocol
    }

    /**
     * Run methods for dispatching input messages from socket to business logic (inside {@link #rpcPeerHandler}) Exits
     * on exceptions or when {@link #POISON_PILL} is found on the dispatch queue
     *
     * @throws InterruptedException in case of thread interruption
     */
    private void dispatchInputMessages() throws InterruptedException {
        syncMetrics.rpcDispatchThreadRunning(+1);
        try {
            while (true) {
                syncMetrics.rpcInputQueueSize(remotePeerId, inputQueue.size());
                final Runnable message = inputQueue.poll(idleDispatchPollTimeoutMs, TimeUnit.MILLISECONDS);
                if (message != null) {
                    if (message == POISON_PILL) {
                        break;
                    }
                    message.run();
                }
                // permitProvider health indicates that system is overloaded, and we are getting backpressure; we need
                // to give up on spamming network and/or reading new messages and let things settle down
                final boolean wantToExit =
                        gossipHalted.get() || (!permitProvider.isHealthy() && !keepSendingEventsWhenUnhealthy);
                if (!rpcPeerHandler.checkForPeriodicActions(wantToExit, !permitProvider.isHealthy())) {
                    // handler told us we are ok to stop processing messages right now due to platform not being healthy
                    processMessages = false;
                }
            }
        } finally {
            syncMetrics.rpcDispatchThreadRunning(-1);
        }
    }

    /**
     * Write all the messages pending in queue, until:
     * <ul>
     * <li>gossip is halted (due to pending reconnect on another connection)</li>
     * <li>permits indicate that system is unhealthy (due to backpressure)</li>
     * <li>we have just detected that this connection is falling behind</li>
     * </ul>
     *
     * @param connection connection over which data should be sent
     */
    private void writeMessages(@NonNull final Connection connection) throws IOException {

        Objects.requireNonNull(connection);

        final SyncOutputStream output = connection.getDos();

        syncMetrics.rpcWriteThreadRunning(+1);
        try {
            while (shouldContinueProcessingMessages()) {
                syncMetrics.rpcOutputQueueSize(remotePeerId, outputQueue.size());
                final StreamWriter message;
                try {
                    final long startNanos = time.nanoTime();
                    message = outputQueue.poll(idleWritePollTimeoutMs, TimeUnit.MILLISECONDS);
                    syncMetrics.outputQueuePollTime(time.nanoTime() - startNanos);
                } catch (final InterruptedException e) {
                    processMessages = false;
                    logger.warn(EXCEPTION.getMarker(), "Interrupted while waiting for message", e);
                    break;
                }
                if (message == null) {
                    final GossipPing ping = pingHandler.possiblyInitiatePing();
                    if (ping != null) {
                        sendPingSameThread(ping, output);
                    }
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
        } finally {
            syncMetrics.rpcWriteThreadRunning(-1);
        }
    }

    /**
     * Why 2 different rules for exiting?
     * <p>
     * <b>processMessagesToSend</b> means remote side said to us they want to end conversation and we should behave
     * <p>
     * <b>gossipHalted</b> means there is reconnect happening very soon, so we need to exit ASAP and free the permits
     * and connection
     *
     * @return true if sending messages loop should continue
     */
    private boolean shouldContinueProcessingMessages() {
        return processMessages && !gossipHalted.get();
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
        syncMetrics.rpcReadThreadRunning(+1);
        try {
            while (true) {
                // check if other side should be already sending us end of conversation marker; if it is the case
                // and they haven't for long enough, break the connection, they might be malicious
                if (conversationFinishPending > 0
                        && time.currentTimeMillis() - conversationFinishPending > maxWaitForConversationFinishMs) {
                    inputQueue.clear();
                    throw new SyncTimeoutException(
                            Duration.ofMillis(time.currentTimeMillis() - conversationFinishPending),
                            Duration.ofMillis(maxWaitForConversationFinishMs));
                }

                final short incomingBatchSize = input.readShort();

                // if remote side said to us it is end of conversation, we need to honor that, as very next byte will
                // be already part of new protocol negotiation
                if (incomingBatchSize == END_OF_CONVERSATION) {
                    break;
                }

                for (int i = 0; i < incomingBatchSize; i++) {

                    final int messageType = input.read();
                    switch (messageType) {
                        case SYNC_DATA:
                            final GossipSyncData gossipSyncData = input.readPbjRecord(GossipSyncData.PROTOBUF);
                            inputQueue.add(() -> receiver.receiveSyncData(SyncData.fromProtobuf(gossipSyncData)));
                            break;
                        case KNOWN_TIPS:
                            final GossipKnownTips knownTips = input.readPbjRecord(GossipKnownTips.PROTOBUF);
                            inputQueue.add(() -> receiver.receiveTips(knownTips.knownTips()));
                            break;
                        case EVENT:
                            final List<GossipEvent> events =
                                    Collections.singletonList(input.readPbjRecord(GossipEvent.PROTOBUF));
                            inputQueue.add(() -> receiver.receiveEvents(events));
                            break;
                        case EVENTS_FINISHED:
                            inputQueue.add(receiver::receiveEventsFinished);
                            break;
                        case PING:
                            pingHandler.handleIncomingPing(input.readPbjRecord(GossipPing.PROTOBUF));
                            break;
                        case PING_REPLY:
                            final GossipPing pingReply = input.readPbjRecord(GossipPing.PROTOBUF);
                            pingHandler.handleIncomingPingReply(pingReply);
                            break;
                    }
                }
            }
        } finally {
            inputQueue.add(POISON_PILL);
            processMessages = false;
            syncMetrics.rpcReadThreadRunning(-1);
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
            final List<List<GossipEvent>> batches = Lists.partition(gossipEvents, EVENT_BATCH_SIZE);
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
    public void sendEndOfEvents() {
        outputQueue.add(out -> {
            out.writeShort(1);
            out.write(EVENTS_FINISHED);
        });
    }

    void sendPingReply(final GossipPing reply) {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
            out.write(PING_REPLY);
            out.writePbjRecord(reply, GossipPing.PROTOBUF);
        });
    }

    private void sendPingSameThread(final GossipPing ping, final SyncOutputStream output) throws IOException {
        output.writeShort(1);
        output.write(PING);
        output.writePbjRecord(ping, GossipPing.PROTOBUF);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void breakConversation() {
        this.conversationFinishPending = time.currentTimeMillis();
        this.processMessages = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cleanup() {
        this.rpcPeerHandler.cleanup();
    }

    public void setRpcPeerHandler(final RpcPeerHandler rpcPeerHandler) {
        this.rpcPeerHandler = rpcPeerHandler;
        this.receiver = rpcPeerHandler;
    }
}

/**
 * Internal interface for encompassing piece of code writing bytes to network stream
 */
@FunctionalInterface
interface StreamWriter {
    void write(SyncOutputStream syncOutputStream) throws IOException;
}
