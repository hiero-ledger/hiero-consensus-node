// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static org.apache.logging.log4j.Level.DEBUG;
import static org.apache.logging.log4j.Level.TRACE;
import static org.apache.logging.log4j.Level.WARN;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.RESET;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.TIMEOUT;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.TOO_FAR_BEHIND;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.util.LoggingUtilities;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.Pipeline;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BlockAcknowledgement;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream.Code;
import org.hiero.block.api.PublishStreamResponse.ResendBlock;
import org.hiero.block.api.PublishStreamResponse.SkipBlock;

/**
 * Manages a single gRPC bidirectional streaming connection to a block node. Each connection:
 * <ul>
 *   <li>Handles the streaming of block items to a configured Block ode</li>
 *   <li>Maintains connection state and handles responses from the Block Node</li>
 *   <li>Coordinates with {@link BlockNodeConnectionManager} for managing the connection lifecycle</li>
 *   <li>Processes block acknowledgements, retries, and error scenarios</li>
 * </ul>
 * <p>
 * The connection goes through multiple states defined in {@link ConnectionState} and
 * uses exponential backoff for retries when errors occur.
 */
public class BlockNodeConnection implements Pipeline<PublishStreamResponse> {

    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);

    /**
     * Counter used to get unique identities for connection instances.
     */
    private static final AtomicLong connectionIdCounter = new AtomicLong(0);
    /**
     * A longer retry delay for when the connection encounters an error.
     */
    public static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);
    /**
     * The configuration specific to the block node this connection is for.
     */
    private final BlockNodeConfig blockNodeConfig;
    /**
     * The "parent" connection manager that manages the lifecycle of this connection.
     */
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    /**
     * Manager that maintains the system-wide state as it pertains to block streaming. Access here is used to retrieve
     * blocks for streaming and indicating which blocks have been acknowledged by the block node.
     */
    private final BlockBufferService blockBufferService;
    /**
     * Metrics API for block stream-specific metrics.
     */
    private final BlockStreamMetrics blockStreamMetrics;
    /**
     * The reset period for the stream. This is used to periodically reset the stream to ensure increased stability and reliability.
     */
    private final Duration streamResetPeriod;
    /**
     * Flag that indicates if this stream is currently shutting down, as initiated by this consensus node.
     */
    private final AtomicBoolean streamShutdownInProgress = new AtomicBoolean(false);
    /**
     * Publish gRPC client used to send messages to the block node.
     */
    private final BlockStreamPublishServiceClient blockStreamPublishServiceClient;

    private final AtomicReference<Pipeline<? super PublishStreamRequest>> requestPipelineRef = new AtomicReference<>();
    /**
     * Reference to the current state of this connection.
     */
    private final AtomicReference<ConnectionState> connectionState;
    /**
     * Scheduled executor service that is used to schedule periodic reset of the stream to help ensure stream health.
     */
    private final ScheduledExecutorService executorService;
    /**
     * This task runs every 24 hours (initial delay of 24 hours) when a connection is active.
     * The task helps maintain stream stability by forcing periodic reconnections.
     * When the connection is closed or reset, this task is cancelled.
     */
    private ScheduledFuture<?> streamResetTask;
    /**
     * The unique ID of this connection instance.
     */
    private final String connectionId;

    /**
     * Represents the possible states of a Block Node connection.
     */
    public enum ConnectionState {
        /**
         * bidi RequestObserver needs to be created.
         */
        UNINITIALIZED(false),
        /**
         * bidi RequestObserver is established but this connection has not been chosen as the active one (priority based).
         */
        PENDING(false),
        /**
         * Connection is active. Block Stream Worker Thread is sending PublishStreamRequest's to the block node through async bidi stream.
         */
        ACTIVE(false),
        /**
         * The connection is being closed. Once in this state, only cleanup operations should be permitted.
         */
        CLOSING(true),
        /**
         * Connection has been closed and pipeline terminated. This is a terminal state.
         * No more requests can be sent and no more responses will be received.
         */
        CLOSED(true);

        private final boolean isTerminal;

        ConnectionState(final boolean isTerminal) {
            this.isTerminal = isTerminal;
        }

        /**
         * @return true if the state represents a terminal or end-state for the connection lifecycle, else false
         */
        boolean isTerminal() {
            return isTerminal;
        }
    }

    /**
     * Helper method to add current instance information for debug logging.
     */
    private void logWithContext(Level level, String message, Object... args) {
        if (logger.isEnabled(level)) {
            message = String.format("%s %s %s", LoggingUtilities.threadInfo(), this, message);
            logger.atLevel(level).log(message, args);
        }
    }

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param configProvider the configuration to use
     * @param nodeConfig the configuration for the block node
     * @param blockNodeConnectionManager the connection manager coordinating block node connections
     * @param blockBufferService the block stream state manager for block node connections
     * @param grpcServiceClient the gRPC client to establish the bidirectional streaming to block node connections
     * @param blockStreamMetrics the block stream metrics for block node connections
     * @param executorService the scheduled executor service used to perform async connection reconnects
     */
    public BlockNodeConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfig nodeConfig,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamPublishServiceClient grpcServiceClient,
            @NonNull final BlockStreamMetrics blockStreamMetrics,
            @NonNull final ScheduledExecutorService executorService) {
        requireNonNull(configProvider, "configProvider must not be null");
        this.blockNodeConfig = requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.blockStreamPublishServiceClient = requireNonNull(grpcServiceClient, "grpcServiceClient must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.connectionState = new AtomicReference<>(ConnectionState.UNINITIALIZED);
        this.executorService = requireNonNull(executorService, "executorService must not be null");
        final var blockNodeConnectionConfig =
                configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
        this.streamResetPeriod = blockNodeConnectionConfig.streamResetPeriod();
        connectionId = String.format("%04d", connectionIdCounter.incrementAndGet());
    }

    /**
     * Creates a new bidi request pipeline for this block node connection.
     */
    public synchronized void createRequestPipeline() {
        if (requestPipelineRef.get() == null) {
            final Pipeline<? super PublishStreamRequest> pipeline =
                    blockStreamPublishServiceClient.publishBlockStream(this);
            requestPipelineRef.set(pipeline);
            logWithContext(DEBUG, "Request pipeline initialized.");
            updateConnectionState(ConnectionState.PENDING);
            blockStreamMetrics.recordConnectionOpened();
        } else {
            logWithContext(DEBUG, "Request pipeline already available.");
        }
    }

    /**
     * Updates the connection's state.
     * @param newState the new state to transition to
     * @return true if the state was successfully updated to the new state, else false
     */
    public boolean updateConnectionState(@NonNull final ConnectionState newState) {
        return updateConnectionState(null, newState);
    }

    /**
     * Updates this connection's state if the current state matches the expected states (if specified).
     *
     * @param expectedCurrentState the expected current connection state (optional)
     * @param newState the new state to transition to
     * @return true if the state was successfully updated to the new state, else false
     */
    private boolean updateConnectionState(
            @Nullable final ConnectionState expectedCurrentState, @NonNull final ConnectionState newState) {
        requireNonNull(newState, "newState must not be null");

        if (expectedCurrentState != null) {
            if (connectionState.compareAndSet(expectedCurrentState, newState)) {
                logWithContext(DEBUG, "Connection state transitioned from {} to {}.", expectedCurrentState, newState);
            } else {
                logWithContext(
                        DEBUG,
                        "Failed to transition state from {} to {} because current state does not match expected state.",
                        expectedCurrentState,
                        newState);
                return false;
            }
        } else {
            final ConnectionState oldState = connectionState.getAndSet(newState);
            logWithContext(DEBUG, "Connection state transitioned from {} to {}.", oldState, newState);
        }

        if (newState == ConnectionState.ACTIVE) {
            scheduleStreamReset();
        } else {
            cancelStreamReset();
        }

        return true;
    }

    /**
     * Schedules the periodic stream reset task to ensure responsiveness and reliability.
     */
    private void scheduleStreamReset() {
        if (streamResetTask != null && !streamResetTask.isDone()) {
            streamResetTask.cancel(false);
        }

        streamResetTask = executorService.scheduleAtFixedRate(
                this::performStreamReset,
                streamResetPeriod.toMillis(),
                streamResetPeriod.toMillis(),
                TimeUnit.MILLISECONDS);

        logWithContext(DEBUG, "Scheduled periodic stream reset every {}.", streamResetPeriod);
    }

    private void performStreamReset() {
        if (getConnectionState() == ConnectionState.ACTIVE) {
            logWithContext(DEBUG, "Performing scheduled stream reset.");
            endTheStreamWith(RESET);
            blockNodeConnectionManager.connectionResetsTheStream(this);
        }
    }

    private void cancelStreamReset() {
        if (streamResetTask != null) {
            streamResetTask.cancel(false);
            streamResetTask = null;
            logWithContext(DEBUG, "Cancelled periodic stream reset.");
        }
    }

    /**
     * Closes the connection and reschedules it with the specified delay.
     * This method ensures proper cleanup and consistent retry logic.
     *
     * @param delay the delay before attempting to reconnect
     */
    private void closeAndReschedule(@Nullable final Duration delay, final boolean callOnComplete) {
        close(callOnComplete);
        blockNodeConnectionManager.rescheduleConnection(this, delay, null, true);
    }

    /**
     * Ends the stream with the specified code and reschedules with a longer retry delay. This method sends an end stream
     * message before cleanup and retry logic.
     *
     * @param code the code indicating why the stream was ended
     */
    private void endStreamAndReschedule(@NonNull final EndStream.Code code) {
        requireNonNull(code, "code must not be null");
        endTheStreamWith(code);
        blockNodeConnectionManager.rescheduleConnection(this, THIRTY_SECONDS, null, true);
    }

    /**
     * Closes the connection and restarts the stream at the specified block number. This method ensures proper cleanup
     * and restart logic for immediate retries.
     *
     * @param blockNumber the block number to restart at
     */
    private void closeAndRestart(final long blockNumber) {
        close(true);
        blockNodeConnectionManager.rescheduleConnection(this, null, blockNumber, false);
    }

    /**
     * Handles the failure of the stream by closing the connection,
     * notifying the connection manager and calling onComplete on the request pipeline.
     */
    public void handleStreamFailure() {
        logWithContext(DEBUG, "Handling failed stream.");
        closeAndReschedule(THIRTY_SECONDS, true);
    }

    /**
     * Handles the failure of the stream by closing the connection,
     * notifying the connection manager without calling onComplete on the request pipeline.
     */
    public void handleStreamFailureWithoutOnComplete() {
        logWithContext(DEBUG, "Handling failed stream without onComplete.");
        closeAndReschedule(THIRTY_SECONDS, false);
    }

    /**
     * Handles the {@link BlockAcknowledgement} response received from the block node.
     *
     * @param acknowledgement the acknowledgement received from the block node
     */
    private void handleAcknowledgement(@NonNull final BlockAcknowledgement acknowledgement) {
        final long acknowledgedBlockNumber = acknowledgement.blockNumber();
        acknowledgeBlocks(acknowledgedBlockNumber, true);

        // Evaluate latency and high-latency QoS via the connection manager
        final var result = blockNodeConnectionManager.recordBlockAckAndCheckLatency(
                blockNodeConfig, acknowledgedBlockNumber, Instant.now());
        if (result.shouldSwitch() && !blockNodeConnectionManager.isOnlyOneBlockNodeConfigured()) {
            logWithContext(
                    DEBUG,
                    "Block node has exceeded high latency threshold {} times consecutively.",
                    result.consecutiveHighLatencyEvents());
            endStreamAndReschedule(TIMEOUT);
        }
    }

    /**
     * Acknowledges the blocks up to the specified block number.
     * @param acknowledgedBlockNumber the block number that has been known to be persisted and verified by the block node
     */
    private void acknowledgeBlocks(final long acknowledgedBlockNumber, final boolean maybeJumpToBlock) {
        logWithContext(DEBUG, "Acknowledging blocks <= {}.", acknowledgedBlockNumber);

        final long currentBlockStreaming = blockNodeConnectionManager.currentStreamingBlockNumber();
        final long currentBlockProducing = blockBufferService.getLastBlockNumberProduced();

        // Update the last verified block by the current connection
        blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, acknowledgedBlockNumber);
        if (maybeJumpToBlock
                && (acknowledgedBlockNumber > currentBlockProducing
                        || acknowledgedBlockNumber > currentBlockStreaming)) {
            /*
            We received an acknowledgement for a block that the consensus node is either currently streaming or
            producing. This likely indicates this consensus node is behind other consensus nodes (since the
            block node would have received the block from another consensus node.) Because of this, we can go
            ahead and jump to the block after the acknowledged one as the next block to send to the block node.
             */
            final long blockToJumpTo = acknowledgedBlockNumber + 1;
            logWithContext(
                    DEBUG,
                    "Received acknowledgement for block {}, later than current streamed ({}) or produced ({}).",
                    acknowledgedBlockNumber,
                    currentBlockStreaming,
                    currentBlockProducing);
            jumpToBlock(blockToJumpTo);
        }
    }

    /**
     * Handles the {@link EndOfStream} response received from the block node.
     * In most cases it indicates that the block node is unable to continue processing.
     * @param endOfStream the EndOfStream response received from the block node
     */
    private void handleEndOfStream(@NonNull final EndOfStream endOfStream) {
        requireNonNull(endOfStream, "endOfStream must not be null");
        final long blockNumber = endOfStream.blockNumber();
        final EndOfStream.Code responseCode = endOfStream.status();

        logWithContext(DEBUG, "Received EndOfStream response (block={}, responseCode={}).", blockNumber, responseCode);

        // Update the latest acknowledged block number
        acknowledgeBlocks(blockNumber, false);

        // Check if we've exceeded the EndOfStream rate limit
        // Record the EndOfStream event and check if the rate limit has been exceeded.
        // The connection manager maintains persistent stats for each node across connections.
        if (blockNodeConnectionManager.recordEndOfStreamAndCheckLimit(blockNodeConfig, Instant.now())) {
            logWithContext(
                    DEBUG,
                    "Block node has exceeded the allowed number of EndOfStream responses (received={}, permitted={}, timeWindow={}). Reconnection scheduled for {}.",
                    blockNodeConnectionManager.getEndOfStreamCount(blockNodeConfig),
                    blockNodeConnectionManager.getMaxEndOfStreamsAllowed(),
                    blockNodeConnectionManager.getEndOfStreamTimeframe(),
                    blockNodeConnectionManager.getEndOfStreamScheduleDelay());
            blockStreamMetrics.recordEndOfStreamLimitExceeded();

            // Schedule delayed retry through connection manager
            closeAndReschedule(blockNodeConnectionManager.getEndOfStreamScheduleDelay(), true);
            return;
        }

        switch (responseCode) {
            case Code.ERROR, Code.PERSISTENCE_FAILED -> {
                // The block node had an end of stream error and cannot continue processing.
                // We should wait for a short period before attempting to retry
                // to avoid overwhelming the node if it's having issues
                logWithContext(
                        DEBUG,
                        "Block node reported an error at block {}. Will attempt to reestablish the stream later.",
                        blockNumber);

                closeAndReschedule(THIRTY_SECONDS, true);
            }
            case Code.TIMEOUT, Code.DUPLICATE_BLOCK, Code.BAD_BLOCK_PROOF, Code.INVALID_REQUEST -> {
                // We should restart the stream at the block immediately
                // following the last verified and persisted block number
                final long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                logWithContext(
                        DEBUG,
                        "Block node reported status indicating immediate restart should be attempted. Will restart stream at block {}.",
                        restartBlockNumber);

                closeAndRestart(restartBlockNumber);
            }
            case Code.SUCCESS -> {
                // The block node orderly ended the stream. In this case, no errors occurred.
                // We should wait for a longer period before attempting to retry.
                logWithContext(DEBUG, "Block node orderly ended the stream at block {}.", blockNumber);
                closeAndReschedule(THIRTY_SECONDS, true);
            }
            case Code.BEHIND -> {
                // The block node is behind us, check if we have the last verified block still available in order to
                // restart the stream from there
                final long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                if (blockBufferService.getBlockState(restartBlockNumber) != null) {
                    logWithContext(
                            DEBUG,
                            "Block node reported it is behind. Will restart stream at block {}.",
                            restartBlockNumber);

                    closeAndRestart(restartBlockNumber);
                } else {
                    // If we don't have the block state, we schedule retry for this connection and establish new one
                    // with different block node
                    logWithContext(DEBUG, "Block node is behind and block state is not available. Ending the stream.");

                    // Indicate that the block node should recover and catch up from another trustworthy block node
                    endStreamAndReschedule(TOO_FAR_BEHIND);
                }
            }
            case Code.UNKNOWN -> {
                // This should never happen, but if it does, schedule this connection for a retry attempt
                // and in the meantime select a new node to stream to
                logWithContext(WARN, "Block node reported an unknown error at block {}.", blockNumber);
                closeAndReschedule(THIRTY_SECONDS, true);
            }
        }
    }

    /**
     * Handles the {@link SkipBlock} response received from the block node.
     * @param skipBlock the SkipBlock response received from the block node
     */
    private void handleSkipBlock(@NonNull final SkipBlock skipBlock) {
        requireNonNull(skipBlock, "skipBlock must not be null");
        final long skipBlockNumber = skipBlock.blockNumber();
        final long streamingBlockNumber = blockNodeConnectionManager.currentStreamingBlockNumber();

        // Only jump if the skip is for the block we are currently processing
        if (skipBlockNumber == streamingBlockNumber) {
            final long nextBlock = skipBlockNumber + 1L;
            logWithContext(DEBUG, "Received SkipBlock response.");
            jumpToBlock(nextBlock); // Now uses signaling instead of thread interruption
        } else {
            logWithContext(
                    DEBUG,
                    "Received SkipBlock response for block {}, but we are streaming block {} so it will be ignored.",
                    skipBlockNumber,
                    streamingBlockNumber);
        }
    }

    /**
     * Handles the {@link ResendBlock} response received from the block node.
     * If the consensus node has the requested block state available, it will start streaming it.
     * Otherwise, it will close the connection and retry with a different block node.
     *
     * @param resendBlock the ResendBlock response received from the block node
     */
    private void handleResendBlock(@NonNull final ResendBlock resendBlock) {
        requireNonNull(resendBlock, "resendBlock must not be null");

        final long resendBlockNumber = resendBlock.blockNumber();
        logWithContext(DEBUG, "Received ResendBlock response for block {}.", resendBlockNumber);

        if (blockBufferService.getBlockState(resendBlockNumber) != null) {
            jumpToBlock(resendBlockNumber);
        } else {
            // If we don't have the block state, we schedule retry for this connection and establish new one
            // with different block node
            logWithContext(
                    DEBUG,
                    "Block node requested a ResendBlock for block {} but that block does not exist on this consensus node. Closing connection and will retry later.",
                    resendBlockNumber);
            closeAndReschedule(THIRTY_SECONDS, true);
        }
    }

    /**
     * Send an EndStream request to end the stream and close the connection.
     *
     * @param code the code on why stream was ended
     */
    public void endTheStreamWith(final PublishStreamRequest.EndStream.Code code) {
        final var earliestBlockNumber = blockBufferService.getEarliestAvailableBlockNumber();
        final var highestAckedBlockNumber = blockBufferService.getHighestAckedBlockNumber();

        // Indicate that the block node should recover and catch up from another trustworthy block node
        final PublishStreamRequest endStream = PublishStreamRequest.newBuilder()
                .endStream(PublishStreamRequest.EndStream.newBuilder()
                        .endCode(code)
                        .earliestBlockNumber(earliestBlockNumber)
                        .latestBlockNumber(highestAckedBlockNumber))
                .build();

        logWithContext(
                DEBUG,
                "Sending EndStream (code={}, earliestBlock={}, latestAcked={}).",
                code,
                earliestBlockNumber,
                highestAckedBlockNumber);
        sendRequest(endStream);
        close(true);
    }

    /**
     * If connection is active sends a stream request to the block node, otherwise does nothing.
     *
     * @param request the request to send
     */
    public void sendRequest(@NonNull final PublishStreamRequest request) {
        requireNonNull(request, "request must not be null");

        final Pipeline<? super PublishStreamRequest> pipeline = requestPipelineRef.get();
        if (getConnectionState() == ConnectionState.ACTIVE && pipeline != null) {
            try {
                if (logger.isDebugEnabled()) {
                    logWithContext(
                            DEBUG,
                            "Sending request to block node (type={}).",
                            request.request().kind());
                } else if (logger.isTraceEnabled()) {
                    /*
                    PublishStreamRequest#protobufSize does the size calculation lazily and thus calling this can incur
                    a performance penality. Therefore, we only want to log the byte size at trace level.
                     */
                    logWithContext(
                            TRACE,
                            "[{}] Sending request to block node (type={}, bytes={})",
                            this,
                            request.request().kind(),
                            request.protobufSize());
                }
                final long startMs = System.currentTimeMillis();
                pipeline.onNext(request);
                final long durationMs = System.currentTimeMillis() - startMs;
                blockStreamMetrics.recordRequestLatency(durationMs);
                logWithContext(TRACE, "[{}] Request took {}ms to send", this, durationMs);

                if (request.hasEndStream()) {
                    blockStreamMetrics.recordRequestEndStreamSent(
                            request.endStream().endCode());
                } else {
                    blockStreamMetrics.recordRequestSent(request.request().kind());
                    blockStreamMetrics.recordBlockItemsSent(
                            request.blockItems().blockItems().size());
                }

                // Record the timestamp when the block is sent
                if (request.blockItems() != null
                        && !request.blockItems().blockItems().isEmpty()) {
                    // Find the first block item
                    // if it is a block proof, record the time it was sent
                    final BlockItem firstItem =
                            request.blockItems().blockItems().getFirst();
                    if (firstItem.hasBlockProof()) {
                        blockNodeConnectionManager.recordBlockProofSent(
                                blockNodeConfig, firstItem.blockProof().block(), Instant.now());
                    }
                }
            } catch (final RuntimeException e) {
                /*
                There is a possible, and somewhat expected, race condition when one thread is attempting to close this
                connection while a request is being sent on another thread. Because of this, an exception may get thrown
                but depending on the state of the connection it may be expected. Thus, if we do get an exception we only
                want to propagate it if the connection is still in an ACTIVE state. If we receive an error while the
                connection is in another state (e.g. CLOSING) then we want to ignore the error.
                 */
                if (getConnectionState() == ConnectionState.ACTIVE) {
                    blockStreamMetrics.recordRequestSendFailure();
                    throw e;
                }
            }
        }
    }

    /**
     * Idempotent operation that closes this connection (if active) and releases associated resources. If there is a
     * failure in closing the connection, the error will be logged and not propagated back to the caller.
     * @param callOnComplete whether to call onComplete on the request pipeline
     */
    public void close(final boolean callOnComplete) {
        final ConnectionState connState = getConnectionState();
        if (connState.isTerminal()) {
            logWithContext(DEBUG, "Connection already in terminal state ({}).", connState);
            return;
        }

        if (!updateConnectionState(connState, ConnectionState.CLOSING)) {
            logWithContext(DEBUG, "State changed while trying to close connection. Aborting close attempt.");
            return;
        }

        logWithContext(DEBUG, "Closing connection.");

        try {
            closePipeline(callOnComplete);
            jumpToBlock(-1L);
            blockStreamMetrics.recordConnectionClosed();
            logWithContext(DEBUG, "Connection successfully closed.");
        } catch (final RuntimeException e) {
            logWithContext(WARN, "Error occurred while attempting to close connection.", e);
        } finally {
            // regardless of outcome, mark the connection as closed
            updateConnectionState(ConnectionState.CLOSED);
        }
    }

    private void closePipeline(final boolean callOnComplete) {
        final Pipeline<? super PublishStreamRequest> pipeline = requestPipelineRef.get();

        if (pipeline != null) {
            logWithContext(DEBUG, "Closing request pipeline for block node.");
            streamShutdownInProgress.set(true);

            try {
                final ConnectionState state = getConnectionState();
                if (state == ConnectionState.CLOSING && callOnComplete) {
                    pipeline.onComplete();
                    logWithContext(DEBUG, "Request pipeline successfully closed.");
                }
            } catch (final Exception e) {
                logWithContext(DEBUG, "Error while completing request pipeline.", e);
            }
            // Clear the pipeline reference to prevent further use
            logWithContext(DEBUG, "Request pipeline removed.", this);
            requestPipelineRef.compareAndSet(pipeline, null);
        }
    }

    /**
     * Returns the block node configuration for this connection.
     *
     * @return the block node configuration
     */
    public BlockNodeConfig getNodeConfig() {
        return blockNodeConfig;
    }

    /**
     * Restarts the worker thread at a specific block number without ending the stream.
     * This method will interrupt the current worker thread if it exists,
     * set the new block number and request index, and start a new worker thread.
     * The gRPC stream with the block node is maintained.
     *
     * @param blockNumber the block number to jump to
     */
    private void jumpToBlock(final long blockNumber) {
        // Set the target block for the worker loop to pick up
        blockNodeConnectionManager.jumpToBlock(blockNumber);
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
        logWithContext(DEBUG, "OnSubscribe invoked.");
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void clientEndStreamReceived() {
        logWithContext(DEBUG, "Client End Stream received.");
        Pipeline.super.clientEndStreamReceived();
    }

    /**
     * Processes responses received from the block node through the bidirectional gRPC stream.
     * Handles {@link BlockAcknowledgement}s, {@link EndOfStream} response signals, {@link SkipBlock} and {@link ResendBlock}.
     *
     * @param response the response received from block node
     */
    @Override
    public void onNext(final @NonNull PublishStreamResponse response) {
        requireNonNull(response, "response must not be null");

        if (getConnectionState() == ConnectionState.CLOSED) {
            logWithContext(DEBUG, "onNext invoked but connection is already closed ({}).", response);
            return;
        }

        // Process the response
        if (response.hasAcknowledgement()) {
            blockStreamMetrics.recordResponseReceived(response.response().kind());
            handleAcknowledgement(response.acknowledgement());
        } else if (response.hasEndStream()) {
            blockStreamMetrics.recordResponseEndOfStreamReceived(
                    response.endStream().status());
            blockStreamMetrics.recordLatestBlockEndOfStream(response.endStream().blockNumber());
            handleEndOfStream(response.endStream());
        } else if (response.hasSkipBlock()) {
            blockStreamMetrics.recordResponseReceived(response.response().kind());
            blockStreamMetrics.recordLatestBlockSkipBlock(response.skipBlock().blockNumber());
            handleSkipBlock(response.skipBlock());
        } else if (response.hasResendBlock()) {
            blockStreamMetrics.recordResponseReceived(response.response().kind());
            blockStreamMetrics.recordLatestBlockResendBlock(
                    response.resendBlock().blockNumber());
            handleResendBlock(response.resendBlock());
        } else {
            blockStreamMetrics.recordUnknownResponseReceived();
            logWithContext(WARN, "Unexpected response received: {}.", response);
        }
    }

    /**
     * Handles errors received on the gRPC stream.
     * Triggers connection retry with appropriate backoff.
     *
     * @param error the error that occurred on the stream
     */
    @Override
    public void onError(final Throwable error) {
        // Suppress errors that happen when the connection is in a terminal state
        if (!getConnectionState().isTerminal()) {
            blockStreamMetrics.recordConnectionOnError();

            if (error instanceof final GrpcException grpcException) {
                logWithContext(DEBUG, "Error received (grpcStatus={}).", grpcException.status(), grpcException);
            } else {
                logWithContext(DEBUG, "Error received.", error);
            }

            handleStreamFailure();
        }
    }

    /**
     * Handles normal stream completion or termination.
     * Triggers reconnection if completion was not initiated by this side.
     */
    @Override
    public void onComplete() {
        blockStreamMetrics.recordConnectionOnComplete();
        if (getConnectionState() == ConnectionState.CLOSED) {
            logWithContext(DEBUG, "onComplete invoked but connection is already closed.");
            return;
        }

        if (streamShutdownInProgress.getAndSet(false)) {
            logWithContext(DEBUG, "Stream completed (stream close was in progress).");
        } else {
            logWithContext(DEBUG, "Stream completed unexpectedly.");
            handleStreamFailure();
        }
    }

    /**
     * Returns the connection state for this connection.
     *
     * @return the connection state
     */
    @NonNull
    public ConnectionState getConnectionState() {
        return connectionState.get();
    }

    @Override
    public String toString() {
        return "[" + connectionId + "/" + blockNodeConfig.address() + ":" + blockNodeConfig.port() + "/"
                + getConnectionState() + "]";
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BlockNodeConnection that = (BlockNodeConnection) o;
        return connectionId == that.connectionId && Objects.equals(blockNodeConfig, that.blockNodeConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockNodeConfig, connectionId);
    }
}
