// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.BROADCAST_EVENT;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.EVENT;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.EVENTS_FINISHED;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.KNOWN_TIPS;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.PING;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.PING_REPLY;
import static org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcMessageId.SYNC_DATA;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.message.GossipKnownTips;
import com.hedera.hapi.platform.message.GossipPing;
import com.hedera.hapi.platform.message.GossipSyncData;
import com.swirlds.base.time.Time;
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
import org.hiero.consensus.concurrent.pool.ParallelExecutionException;
import org.hiero.consensus.concurrent.pool.ParallelExecutor;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncPermitProvider;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcReceiver;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcReceiverHandler;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcSender;
import org.hiero.consensus.gossip.impl.gossip.rpc.SyncData;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncPhase;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncTimeoutException;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.protocol.SyncStatusChecker;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkMetrics;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.protocol.PeerProtocol;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;


record TimeAndRunnable(long nano, Runnable run) {};

/**
 * Message based implementation of gossip; currently supporting sync and simplistic broadcast. Responsible for
 * communication with a single peer
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
    private final BlockingQueue<StreamWriter> outputQueue;

    /**
     * All incoming messages from remote node to be passed to dispatch thread
     */
    private final BlockingQueue<TimeAndRunnable> inputQueue;

    /**
     * Helper class to handle ping logic
     */
    private final RpcPingHandler pingHandler;

    /** A duration between reporting full stack traces for socket exceptions. */
    private static final Duration SOCKET_EXCEPTION_DURATION = Duration.ofMinutes(1);

    /**
     * Internal log rate limiter to avoid spamming logs
     */
    private final RateLimiter exceptionRateLimiter;

    /**
     * Pluggable exception handler, mostly useful for testing.
     */
    private final RpcInternalExceptionHandler exceptionHandler;

    /**
     * Configuration for sync parameters
     */
    private final SyncConfig syncConfig;

    /**
     * State machine for rpc exchange process (mostly sync process)
     */
    private GossipRpcReceiverHandler rpcPeerHandler;

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
     * Marker bool to exit processing output queue early in case we need to give control back to other protocols
     */
    private volatile boolean processMessages = false;

    /**
     * At what time conversation was stopped, used to measure possible timeout
     */
    private volatile long conversationFinishPending;

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
     * Helper class for checking if rpc communication is overloaded (ping or output queue) and informs to disable
     * broadcast if it is the case
     */
    private final RpcOverloadMonitor overloadMonitor;

    /**
     * Constructs a new rpc protocol
     *
     * @param peerId           the id of the peer being synced with in this protocol
     * @param executor         executor to run parallel network tasks
     * @param gossipHalted     returns true if gossip is halted, false otherwise
     * @param platformStatus   provides the current platform status
     * @param permitProvider   provides permits to sync
     * @param networkMetrics   network metrics to register data about communication traffic and latencies
     * @param time             the {@link Time} instance for the platformeturns the {@link Time} instance for the
     *                         platform
     * @param syncMetrics      metrics tracking syncing
     * @param syncConfig       sync configuration
     * @param broadcastConfig  broadcast configuration
     * @param exceptionHandler handler for errors which happens when managing the connection/dispatch loop
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
            @NonNull final SyncConfig syncConfig,
            @NonNull final BroadcastConfig broadcastConfig,
            @NonNull final RpcInternalExceptionHandler exceptionHandler) {
        this.executor = Objects.requireNonNull(executor);
        this.remotePeerId = Objects.requireNonNull(peerId);
        this.gossipHalted = Objects.requireNonNull(gossipHalted);
        this.platformStatus = Objects.requireNonNull(platformStatus);
        this.permitProvider = Objects.requireNonNull(permitProvider);
        this.time = Objects.requireNonNull(time);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.syncConfig = syncConfig;
        this.pingHandler = new RpcPingHandler(time, networkMetrics, remotePeerId, this);

        this.exceptionRateLimiter = new RateLimiter(time, SOCKET_EXCEPTION_DURATION);
        this.exceptionHandler = exceptionHandler;

        this.inputQueue =
                syncMetrics.createMeasuredQueue("rpc_input_%02d".formatted(peerId.id()), new LinkedBlockingQueue<>());
        this.outputQueue =
                syncMetrics.createMeasuredQueue("rpc_output_%02d".formatted(peerId.id()), new LinkedBlockingQueue<>());
        this.overloadMonitor = new RpcOverloadMonitor(
                broadcastConfig, syncMetrics, time, (overload) -> rpcPeerHandler.setCommunicationOverloaded(overload));
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
            exceptionHandler.handleNetworkException(e, connection, exceptionRateLimiter);
        } finally {
            inputQueue.clear();
            outputQueue.clear();
            rpcPeerHandler.cleanup();
            permitProvider.release();
            previousPhase = syncMetrics.reportSyncPhase(remotePeerId, SyncPhase.OUTSIDE_OF_RPC);
        }
        // later we will loop here, for now just exit the protocol
    }

    static long WAIT_NANOS = 0_000_000;

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
                final TimeAndRunnable tar = inputQueue.poll(syncConfig.rpcIdleDispatchPollTimeout().toMillis(), TimeUnit.MILLISECONDS);

                if (tar != null) {
                    final Runnable message = tar.run();
                    if (message == POISON_PILL) {
                        break;
                    }

                    long since = System.nanoTime()-tar.nano();
                    while ( since < WAIT_NANOS) {
                        Thread.sleep(0,1_000);
                        since = System.nanoTime()-tar.nano();
                    }

                    message.run();
                }
                // permitProvider health indicates that system is overloaded, and we are getting backpressure; we need
                // to give up on spamming network and/or reading new messages and let things settle down
                final boolean wantToExit = gossipHalted.get()
                        || (!permitProvider.isHealthy() && !syncConfig.keepSendingEventsWhenUnhealthy());
                if (!rpcPeerHandler.checkForPeriodicActions(wantToExit, !permitProvider.isHealthy())) {
                    // handler told us we are ok to stop processing messages right now due to platform not being healthy
                    processMessages = false;
                }
                overloadMonitor.reportOutputQueueSize(outputQueue.size());
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
                final StreamWriter message;
                try {
                    final long startNanos = time.nanoTime();
                    message = outputQueue.poll(
                            syncConfig.rpcIdleWritePollTimeout().toMillis(), TimeUnit.MILLISECONDS);
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
                        && time.currentTimeMillis() - conversationFinishPending
                                > syncConfig.maxSyncTime().toMillis()) {
                    inputQueue.clear();
                    throw new SyncTimeoutException(
                            Duration.ofMillis(time.currentTimeMillis() - conversationFinishPending),
                            syncConfig.maxSyncTime());
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
                            inputQueue.add(new TimeAndRunnable(System.nanoTime(), () -> receiver.receiveSyncData(SyncData.fromProtobuf(gossipSyncData))));
                            break;
                        case KNOWN_TIPS:
                            final GossipKnownTips knownTips = input.readPbjRecord(GossipKnownTips.PROTOBUF);
                            inputQueue.add(new TimeAndRunnable(System.nanoTime(), () -> receiver.receiveTips(knownTips.knownTips())));
                            break;
                        case EVENT:
                            final List<GossipEvent> events =
                                    Collections.singletonList(input.readPbjRecord(GossipEvent.PROTOBUF));
                            inputQueue.add(new TimeAndRunnable(System.nanoTime(), () -> receiver.receiveEvents(events)));
                            break;
                        case BROADCAST_EVENT:
                            final GossipEvent event = input.readPbjRecord(GossipEvent.PROTOBUF);
                            inputQueue.add(new TimeAndRunnable(System.nanoTime(), () -> receiver.receiveBroadcastEvent(event)));
                            break;
                        case EVENTS_FINISHED:
                            inputQueue.add(new TimeAndRunnable(System.nanoTime(), receiver::receiveEventsFinished));
                            break;
                        case PING:
                            pingHandler.handleIncomingPing(input.readPbjRecord(GossipPing.PROTOBUF));
                            break;
                        case PING_REPLY:
                            final GossipPing pingReply = input.readPbjRecord(GossipPing.PROTOBUF);
                            final long pingMillis =
                                    TimeUnit.NANOSECONDS.toMillis(pingHandler.handleIncomingPingReply(pingReply));
                            overloadMonitor.reportPing(pingMillis);
                            break;
                    }
                }
            }
        } finally {
            inputQueue.add(new TimeAndRunnable(System.nanoTime(), POISON_PILL));
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
            for (int i = 0; i < gossipEvents.size(); i += EVENT_BATCH_SIZE) {
                final List<GossipEvent> batch =
                        gossipEvents.subList(i, Math.min(i + EVENT_BATCH_SIZE, gossipEvents.size()));
                if (!batch.isEmpty()) {
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
    public void sendBroadcastEvent(@NonNull final GossipEvent gossipEvent) {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
            out.write(BROADCAST_EVENT);
            out.writePbjRecord(gossipEvent, GossipEvent.PROTOBUF);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendEndOfEvents() {
        outputQueue.add(out -> {
            out.writeShort(1); // single message
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
        output.writeShort(1); // single message
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

    public void setRpcPeerHandler(final GossipRpcReceiverHandler rpcPeerHandler) {
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
