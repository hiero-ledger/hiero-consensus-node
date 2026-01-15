// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.ERROR;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.RESET;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.TIMEOUT;
import static org.hiero.block.api.PublishStreamRequest.EndStream.Code.TOO_FAR_BEHIND;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStats.HighLatencyResult;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.Pipeline;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamRequest.EndStream;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BehindPublisher;
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
public class BlockNodeStreamingConnection extends AbstractBlockNodeConnection
        implements Pipeline<PublishStreamResponse> {

    private static final Logger logger = LogManager.getLogger(BlockNodeStreamingConnection.class);

    /**
     * A longer retry delay for when the connection encounters an error.
     */
    public static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);
    /**
     * The "parent" connection manager that manages the lifecycle of this connection.
     */
    private final BlockNodeConnectionManager connectionManager;
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
     * Timeout for pipeline onNext() and onComplete() operations to detect unresponsive block nodes.
     */
    private final Duration pipelineOperationTimeout;
    /**
     * Flag that indicates if this stream is currently shutting down, as initiated by this consensus node.
     */
    private final AtomicBoolean streamShutdownInProgress = new AtomicBoolean(false);
    /**
     * Publish gRPC client used to send messages to the block node.
     */
    private BlockStreamPublishServiceClient client;

    private final AtomicReference<Pipeline<? super PublishStreamRequest>> requestPipelineRef = new AtomicReference<>();
    /**
     * Scheduled executor service that is used to schedule periodic reset of the stream to help ensure stream health.
     */
    private final ScheduledExecutorService executorService;
    /**
     * Executor service used to perform asynchronous, blocking I/O operations.
     */
    private final ExecutorService blockingIoExecutor;
    /**
     * This task runs every 24 hours (initial delay of 24 hours) when a connection is active.
     * The task helps maintain stream stability by forcing periodic reconnections.
     * When the connection is closed or reset, this task is cancelled.
     */
    private ScheduledFuture<?> streamResetTask;
    /**
     * The current block number being streamed.
     */
    private final AtomicLong streamingBlockNumber = new AtomicLong(-1);
    /**
     * Reference to the worker thread, once it is initialized.
     */
    private final AtomicReference<Thread> workerThreadRef = new AtomicReference<>();
    /**
     * Factory used to create the block node clients.
     */
    private final BlockNodeClientFactory clientFactory;
    /**
     * Flag indicating if this connection should be closed at the next block boundary. For example: if set to true while
     * the connection is actively streaming a block, then the connection will continue to stream the remaining block and
     * once it is finished it will close the connection.
     */
    private final AtomicBoolean closeAtNextBlockBoundary = new AtomicBoolean(false);

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param configProvider the configuration to use
     * @param nodeConfig the configuration for the block node
     * @param connectionManager the connection manager coordinating block node connections
     * @param blockBufferService the block stream state manager for block node connections
     * @param blockStreamMetrics the block stream metrics for block node connections
     * @param executorService the scheduled executor service used to perform async connection reconnects
     * @param blockingIoExecutor the executor service used for blocking I/O operations (e.g. sending a message)
     * @param initialBlockToStream the initial block number to start streaming from, or null to use default
     * @param clientFactory the factory for creating block stream clients
     */
    public BlockNodeStreamingConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfiguration nodeConfig,
            @NonNull final BlockNodeConnectionManager connectionManager,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamMetrics blockStreamMetrics,
            @NonNull final ScheduledExecutorService executorService,
            @NonNull final ExecutorService blockingIoExecutor,
            @Nullable final Long initialBlockToStream,
            @NonNull final BlockNodeClientFactory clientFactory) {
        super(ConnectionType.BLOCK_STREAMING, nodeConfig, configProvider);
        this.connectionManager = requireNonNull(connectionManager, "blockNodeConnectionManager must not be null");
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.executorService = requireNonNull(executorService, "executorService must not be null");
        this.blockingIoExecutor = requireNonNull(blockingIoExecutor, "Blocking I/O executor must not be null");
        final var blockNodeConnectionConfig =
                configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
        this.streamResetPeriod = blockNodeConnectionConfig.streamResetPeriod();
        this.clientFactory = requireNonNull(clientFactory, "clientFactory must not be null");
        this.pipelineOperationTimeout = blockNodeConnectionConfig.pipelineOperationTimeout();

        if (initialBlockToStream != null) {
            streamingBlockNumber.set(initialBlockToStream);
            logger.info(
                    "{} Block node connection will initially stream with block {}",
                    BlockNodeStreamingConnection.this,
                    initialBlockToStream);
        }
    }

    /**
     * Creates a new bidi request pipeline for this block node connection.
     */
    @Override
    public synchronized void initialize() {
        if (requestPipelineRef.get() != null) {
            logger.debug("{} Request pipeline already available.", this);
            return;
        }

        final Duration timeoutDuration = configProvider()
                .getConfiguration()
                .getConfigData(BlockNodeConnectionConfig.class)
                .grpcOverallTimeout();

        // Execute entire pipeline creation (including gRPC client creation) with timeout
        // to prevent blocking on network operations
        final Future<?> future = blockingIoExecutor.submit(() -> {
            client = clientFactory.createStreamingClient(configuration(), timeoutDuration);
            final Pipeline<? super PublishStreamRequest> pipeline = client.publishBlockStream(this);
            requestPipelineRef.set(pipeline);
        });

        try {
            future.get(pipelineOperationTimeout.toMillis(), TimeUnit.MILLISECONDS);
            logger.debug("{} Request pipeline initialized.", this);
            updateConnectionState(ConnectionState.READY);
            blockStreamMetrics.recordConnectionOpened();
        } catch (final TimeoutException e) {
            future.cancel(true);
            logger.warn("{} Pipeline creation timed out after {}ms", this, pipelineOperationTimeout.toMillis());
            blockStreamMetrics.recordPipelineOperationTimeout();
            throw new RuntimeException("Pipeline creation timed out", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("{} Interrupted while creating pipeline", this, e);
            throw new RuntimeException("Interrupted while creating pipeline", e);
        } catch (final ExecutionException e) {
            logger.warn("{} Error creating pipeline", this, e.getCause());
            throw new RuntimeException("Error creating pipeline", e.getCause());
        }
    }

    @Override
    void onActiveStateTransition() {
        scheduleStreamReset();
        // start worker thread to handle sending requests
        final Thread workerThread = new Thread(new ConnectionWorkerLoopTask(), "bn-conn-worker-" + connectionId());
        if (workerThreadRef.compareAndSet(null, workerThread)) {
            workerThread.start();
        }
    }

    @Override
    void onTerminalStateTransition() {
        if (streamResetTask != null) {
            streamResetTask.cancel(false);
            streamResetTask = null;
            logger.debug("{} Cancelled periodic stream reset.", this);
        }
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

        logger.debug("{} Scheduled periodic stream reset every {}.", this, streamResetPeriod);
    }

    private void performStreamReset() {
        if (isActive()) {
            logger.info("{} Performing scheduled stream reset.", this);
            endTheStreamWith(RESET);
            connectionManager.selectNewBlockNodeForStreaming(false);
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
        connectionManager.rescheduleConnection(this, delay, null, true);
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
        connectionManager.rescheduleConnection(this, THIRTY_SECONDS, null, true);
    }

    /**
     * Closes the connection and restarts the stream at the specified block number. This method ensures proper cleanup
     * and restart logic for immediate retries.
     *
     * @param blockNumber the block number to restart at
     */
    private void closeAndRestart(final long blockNumber) {
        close(true);
        connectionManager.rescheduleConnection(this, null, blockNumber, false);
    }

    /**
     * Handles the failure of the stream by closing the connection,
     * notifying the connection manager and calling onComplete on the request pipeline.
     */
    public void handleStreamFailure() {
        logger.info("{} Handling failed stream.", this);
        closeAndReschedule(THIRTY_SECONDS, true);
    }

    /**
     * Handles the failure of the stream by closing the connection,
     * notifying the connection manager without calling onComplete on the request pipeline.
     */
    public void handleStreamFailureWithoutOnComplete() {
        logger.info("{} Handling failed stream without onComplete.", this);
        closeAndReschedule(THIRTY_SECONDS, false);
    }

    /**
     * Handles the {@link BlockAcknowledgement} response received from the block node.
     *
     * @param acknowledgement the acknowledgement received from the block node
     */
    private void handleAcknowledgement(@NonNull final BlockAcknowledgement acknowledgement) {
        final long acknowledgedBlockNumber = acknowledgement.blockNumber();
        logger.debug("{} BlockAcknowledgement received for block {}.", this, acknowledgedBlockNumber);
        acknowledgeBlocks(acknowledgedBlockNumber, true);

        // Evaluate latency and high-latency QoS via the connection manager
        final HighLatencyResult result = connectionManager.recordBlockAckAndCheckLatency(
                configuration(), acknowledgedBlockNumber, Instant.now());
        if (result.shouldSwitch() && !connectionManager.isOnlyOneBlockNodeConfigured()) {
            if (logger.isInfoEnabled()) {
                logger.info(
                        "{} Block node has exceeded high latency threshold {} times consecutively.",
                        this,
                        result.consecutiveHighLatencyEvents());
            }
            endStreamAndReschedule(TIMEOUT);
        }
    }

    private void updateAcknowledgementMetrics(long acknowledgedBlockNumber) {
        final long currentBlockProducing = blockBufferService.getLastBlockNumberProduced();
        // Record latencies for all acknowledged blocks
        if (acknowledgedBlockNumber != Long.MAX_VALUE) {
            final long nowMs = System.currentTimeMillis();

            final long previousAcknowledgedBlockNumber = blockBufferService.getHighestAckedBlockNumber();
            final long lowestAvailableBlockInBuffer = blockBufferService.getEarliestAvailableBlockNumber();

            final long start = Math.max(previousAcknowledgedBlockNumber + 1, lowestAvailableBlockInBuffer);
            final long end = Math.min(acknowledgedBlockNumber, currentBlockProducing);

            if (start <= end) {
                for (long blkNum = start; blkNum <= end; blkNum++) {
                    final BlockState blockState = blockBufferService.getBlockState(blkNum);
                    if (blockState != null) {
                        if (blockState.openedTimestamp() != null) {
                            final long headerProducedToAckMs =
                                    nowMs - blockState.openedTimestamp().toEpochMilli();
                            blockStreamMetrics.recordHeaderProducedToAckLatency(headerProducedToAckMs);
                        }
                        if (blockState.closedTimestamp() != null) {
                            final long blockClosedToAckMs =
                                    nowMs - blockState.closedTimestamp().toEpochMilli();
                            blockStreamMetrics.recordBlockClosedToAckLatency(blockClosedToAckMs);
                        }
                        if (blockState.getHeaderSentMs() != null) {
                            final long latencyMs = nowMs - blockState.getHeaderSentMs();
                            blockStreamMetrics.recordHeaderSentAckLatency(latencyMs);
                        }
                        if (blockState.getBlockEndSentMs() != null) {
                            final long latencyMs = nowMs - blockState.getBlockEndSentMs();
                            blockStreamMetrics.recordBlockEndSentToAckLatency(latencyMs);
                        }
                    }
                }
            }
        }
    }

    /**
     * Acknowledges the blocks up to the specified block number.
     * @param acknowledgedBlockNumber the block number that has been known to be persisted and verified by the block node
     */
    private void acknowledgeBlocks(final long acknowledgedBlockNumber, final boolean maybeJumpToBlock) {
        logger.debug("{} Acknowledging blocks <= {}.", this, acknowledgedBlockNumber);

        final long currentBlockStreaming = streamingBlockNumber.get();
        final long currentBlockProducing = blockBufferService.getLastBlockNumberProduced();

        updateAcknowledgementMetrics(acknowledgedBlockNumber);

        // Update the last verified block by the current connection
        blockBufferService.setLatestAcknowledgedBlock(acknowledgedBlockNumber);

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
            logger.debug(
                    "{} Received acknowledgement for block {}, later than current streamed ({}) or produced ({}).",
                    this,
                    acknowledgedBlockNumber,
                    currentBlockStreaming,
                    currentBlockProducing);
            streamingBlockNumber.updateAndGet(current -> Math.max(current, blockToJumpTo));
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

        logger.info("{} Received EndOfStream response (block={}, responseCode={}).", this, blockNumber, responseCode);

        // Update the latest acknowledged block number
        acknowledgeBlocks(blockNumber, false);

        // Check if we've exceeded the EndOfStream rate limit
        // Record the EndOfStream event and check if the rate limit has been exceeded.
        // The connection manager maintains persistent stats for each node across connections.
        if (connectionManager.recordEndOfStreamAndCheckLimit(configuration(), Instant.now())) {
            if (logger.isInfoEnabled()) {
                logger.info(
                        "{} Block node has exceeded the allowed number of EndOfStream responses "
                                + "(received={}, permitted={}, timeWindow={}). Reconnection scheduled for {}.",
                        this,
                        connectionManager.getEndOfStreamCount(configuration()),
                        connectionManager.getMaxEndOfStreamsAllowed(),
                        connectionManager.getEndOfStreamTimeframe(),
                        connectionManager.getEndOfStreamScheduleDelay());
            }
            blockStreamMetrics.recordEndOfStreamLimitExceeded();

            // Schedule delayed retry through connection manager
            closeAndReschedule(connectionManager.getEndOfStreamScheduleDelay(), true);
            return;
        }

        switch (responseCode) {
            case Code.ERROR, Code.PERSISTENCE_FAILED -> {
                // The block node had an end of stream error and cannot continue processing.
                // We should wait for a short period before attempting to retry
                // to avoid overwhelming the node if it's having issues
                logger.info(
                        "{} Block node reported an error at block {}. Will attempt to reestablish the stream later.",
                        this,
                        blockNumber);

                closeAndReschedule(THIRTY_SECONDS, true);
            }
            case Code.TIMEOUT, Code.DUPLICATE_BLOCK, Code.BAD_BLOCK_PROOF, Code.INVALID_REQUEST -> {
                // We should restart the stream at the block immediately
                // following the last verified and persisted block number
                final long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                logger.info(
                        "{} Block node reported status indicating immediate restart should be attempted. "
                                + "Will restart stream at block {}.",
                        this,
                        restartBlockNumber);

                closeAndRestart(restartBlockNumber);
            }
            case Code.SUCCESS -> {
                // The block node orderly ended the stream. In this case, no errors occurred.
                // We should wait for a longer period before attempting to retry.
                logger.info("{} Block node orderly ended the stream at block {}.", this, blockNumber);
                closeAndReschedule(THIRTY_SECONDS, true);
            }
            case Code.UNKNOWN -> {
                // This should never happen, but if it does, schedule this connection for a retry attempt
                // and in the meantime select a new node to stream to
                logger.info("{} Block node reported an unknown error at block {}.", this, blockNumber);
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
        final long activeBlockNumber = streamingBlockNumber.get();

        // Only jump if the skip is for the block we are currently processing
        if (skipBlockNumber == activeBlockNumber) {
            final long nextBlock = skipBlockNumber + 1;
            if (streamingBlockNumber.compareAndSet(activeBlockNumber, nextBlock)) {
                logger.debug("{} Received SkipBlock response; skipping to block {}", this, nextBlock);
                return;
            }
        }

        logger.debug(
                "{} Received SkipBlock response (blockToSkip={}), but we've moved on to another block. "
                        + "Ignoring skip request",
                this,
                skipBlockNumber);
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
        logger.debug("{} Received ResendBlock response for block {}.", this, resendBlockNumber);

        if (blockBufferService.getBlockState(resendBlockNumber) != null) {
            streamingBlockNumber.set(resendBlockNumber);
        } else {
            // If we don't have the block state, we schedule retry for this connection and establish new one
            // with different block node
            logger.info(
                    "{} Block node requested a ResendBlock for block {} but that block does not exist "
                            + "on this consensus node. Closing connection and will retry later.",
                    this,
                    resendBlockNumber);

            if (resendBlockNumber < blockBufferService.getEarliestAvailableBlockNumber()) {
                // Indicate that the block node should catch up from another trustworthy block node
                endStreamAndReschedule(TOO_FAR_BEHIND);
            } else if (resendBlockNumber > blockBufferService.getLastBlockNumberProduced()) {
                endStreamAndReschedule(ERROR);
            }
        }
    }

    /**
     * Handles the {@link BehindPublisher} response received from the block node.
     * If the consensus node has the requested block state available, it will start streaming it.
     * Otherwise, it will close the connection and retry with a different block node.
     *
     * @param nodeBehind the BehindPublisher response received from the block node
     */
    private void handleBlockNodeBehind(@NonNull final BehindPublisher nodeBehind) {
        requireNonNull(nodeBehind, "nodeBehind must not be null");
        final long blockNumber = nodeBehind.blockNumber();
        logger.info("{} Received BehindPublisher response for block {}.", this, blockNumber);

        final long blockToStream = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
        // The block node is behind us, check if we have the last verified block still available
        // to start streaming from there
        if (blockBufferService.getBlockState(blockToStream) != null) {
            logger.info("{} Block node reported it is behind. Will start streaming block {}.", this, blockToStream);

            streamingBlockNumber.set(blockToStream);
        } else {
            // If we don't have the block state, we schedule retry for this connection
            // and establish new one with different block node
            logger.info("{} Block node is behind and block state is not available. Ending the stream.", this);

            if (blockToStream < blockBufferService.getEarliestAvailableBlockNumber()) {
                // Indicate that the block node should catch up from another trustworthy block node
                endStreamAndReschedule(TOO_FAR_BEHIND);
            } else if (blockToStream > blockBufferService.getLastBlockNumberProduced()) {
                endStreamAndReschedule(ERROR);
            }
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

        logger.info(
                "{} Sending EndStream (code={}, earliestBlock={}, latestAcked={}).",
                this,
                code,
                earliestBlockNumber,
                highestAckedBlockNumber);
        try {
            sendRequest(new EndStreamRequest(endStream));
        } catch (final RuntimeException e) {
            logger.warn("{} Error sending EndStream request", this, e);
        }
        close(true);
    }

    /**
     * Sends the specified request over this connection, if active, to a block node. If the connection is not active,
     * then no operations are performed. If there was a timeout trying to send the request, then the connection will be
     * closed, otherwise any failures in sending the request will cause a runtime exception to be thrown, unless the
     * error is caught after the connection has transitioned to a terminal state in which case the error will be suppressed.
     *
     * @param request the request to send
     * @return true if the request was sent successfully, else false if the connection isn't active or initialized
     * @throws RuntimeException if there was a failure sending the request
     */
    private boolean sendRequest(@NonNull final StreamRequest request) {
        requireNonNull(request, "request must not be null");

        final Pipeline<? super PublishStreamRequest> pipeline = requestPipelineRef.get();

        if (!isActive() || pipeline == null) {
            logger.debug(
                    "{} Tried to send a request but the connection is not active or initialized; ignoring request",
                    this);
            return false;
        }

        if (request instanceof final BlockRequest br) {
            logger.debug(
                    "{} [block={}, request={}] Sending request to block node (type={})",
                    this,
                    br.blockNumber(),
                    br.requestNumber(),
                    br.streamRequestType());
        } else {
            logger.debug("{} Sending ad hoc request to block node (type={})", this, request.streamRequestType());
        }

        final long startMs = System.currentTimeMillis();
        long sentMs = 0;

        try {
            final Future<?> future = blockingIoExecutor.submit(() -> pipeline.onNext(request.streamRequest()));
            try {
                future.get(pipelineOperationTimeout.toMillis(), TimeUnit.MILLISECONDS);
                sentMs = System.currentTimeMillis();
            } catch (final TimeoutException e) {
                future.cancel(true); // Cancel the task if it times out
                if (isActive()) {
                    logger.debug(
                            "{} Pipeline onNext() timed out after {}ms", this, pipelineOperationTimeout.toMillis());
                    blockStreamMetrics.recordPipelineOperationTimeout();
                    handleStreamFailure();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                logger.debug("{} Interrupted while waiting for pipeline.onNext()", this, e);
                throw new RuntimeException("Interrupted while waiting for pipeline.onNext()", e);
            } catch (final ExecutionException e) {
                logger.debug("{} Error executing pipeline.onNext()", this, e.getCause());
                throw new RuntimeException("Error executing pipeline.onNext()", e.getCause());
            }
        } catch (final RuntimeException e) {
            /*
            There is a possible, and somewhat expected, race condition when one thread is attempting to close this
            connection while a request is being sent on another thread. Because of this, an exception may get thrown
            but depending on the state of the connection it may be expected. Thus, if we do get an exception we only
            want to propagate it if the connection is still in an ACTIVE state. If we receive an error while the
            connection is in another state (e.g. CLOSING) then we want to ignore the error.
             */
            if (isActive()) {
                blockStreamMetrics.recordRequestSendFailure();
                throw e;
            } else {
                logger.debug(
                        "{} Error occurred while sending request, but the connection is no longer active; suppressing error",
                        this,
                        e);
                return false;
            }
        }

        final long durationMs = sentMs - startMs;
        blockStreamMetrics.recordRequestLatency(durationMs);

        if (request instanceof final BlockRequest br) {
            logger.trace(
                    "{} [block={}, request={}] Request took {}ms to send",
                    this,
                    br.blockNumber(),
                    br.requestNumber(),
                    durationMs);
        } else {
            logger.trace("{} Ad hoc request took {}ms to send", this, durationMs);
        }

        switch (request) {
            case final EndStreamRequest r -> blockStreamMetrics.recordRequestEndStreamSent(r.code());
            case final BlockRequest br -> {
                switch (br) {
                    case final BlockEndRequest r -> blockStreamMetrics.recordRequestSent(r.streamRequestType());
                    case final BlockItemsStreamRequest r -> {
                        blockStreamMetrics.recordRequestSent(r.streamRequestType());
                        blockStreamMetrics.recordBlockItemsSent(r.numItems());
                        if (r.hasBlockProof()) {
                            connectionManager.recordBlockProofSent(
                                    configuration(), r.blockNumber(), Instant.ofEpochMilli(sentMs));
                        }
                        if (r.hasBlockHeader()) {
                            final BlockState blockState = blockBufferService.getBlockState(r.blockNumber());
                            if (blockState != null) {
                                blockState.setHeaderSentMs(sentMs);
                            }
                        }
                        blockStreamMetrics.recordRequestBlockItemCount(r.numItems());
                        blockStreamMetrics.recordRequestBytes(r.streamRequest().protobufSize());
                    }
                }
            }
        }

        return true;
    }

    @Override
    public void close() {
        close(true);
    }

    /**
     * Idempotent operation that closes this connection (if active) and releases associated resources. If there is a
     * failure in closing the connection, the error will be logged and not propagated back to the caller.
     * @param callOnComplete whether to call onComplete on the request pipeline
     */
    void close(final boolean callOnComplete) {
        final ConnectionState status = currentState();
        if (currentState().isTerminal()) {
            logger.debug("{} Connection already in terminal state ({}).", this, status);
            return;
        }

        if (!updateConnectionState(status, ConnectionState.CLOSING)) {
            logger.debug("{} State changed while trying to close connection. Aborting close attempt.", this);
            return;
        }

        logger.info("{} Closing connection.", this);

        try {
            closePipeline(callOnComplete);
            logger.debug("{} Connection successfully closed.", this);
        } catch (final RuntimeException e) {
            logger.warn("{} Error occurred while attempting to close connection.", this, e);
        } finally {
            try {
                if (client != null) {
                    client.close();
                }
            } catch (final Exception e) {
                logger.error("{} Error occurred while closing gRPC client.", this, e);
            }
            try {
                blockingIoExecutor.shutdown();
                if (!blockingIoExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    blockingIoExecutor.shutdownNow();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                blockingIoExecutor.shutdownNow();
                logger.error("{} Error occurred while shutting down pipeline executor.", this, e);
            }
            blockStreamMetrics.recordConnectionClosed();
            blockStreamMetrics.recordActiveConnectionIp(-1L);
            connectionManager.notifyConnectionClosed(this);
            // regardless of outcome, mark the connection as closed
            updateConnectionState(ConnectionState.CLOSED);
        }
    }

    private void closePipeline(final boolean callOnComplete) {
        final Pipeline<? super PublishStreamRequest> pipeline = requestPipelineRef.get();

        if (pipeline != null) {
            logger.debug("{} Closing request pipeline for block node.", this);
            streamShutdownInProgress.set(true);

            try {
                if (currentState() == ConnectionState.CLOSING && callOnComplete) {
                    final Future<?> future = blockingIoExecutor.submit(pipeline::onComplete);
                    try {
                        future.get(pipelineOperationTimeout.toMillis(), TimeUnit.MILLISECONDS);
                        logger.debug("{} Request pipeline successfully closed.", this);
                    } catch (final TimeoutException e) {
                        future.cancel(true); // Cancel the task if it times out
                        logger.debug(
                                "{} Pipeline onComplete() timed out after {}ms",
                                this,
                                pipelineOperationTimeout.toMillis());
                        blockStreamMetrics.recordPipelineOperationTimeout();
                        // Connection is already closing, just log the timeout
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt(); // Restore interrupt status
                        logger.debug("{} Interrupted while waiting for pipeline.onComplete()", this);
                    } catch (final ExecutionException e) {
                        logger.debug("{} Error executing pipeline.onComplete()", this, e.getCause());
                    }
                }
            } catch (final Exception e) {
                logger.warn("{} Error while completing request pipeline.", this, e);
            }
            // Clear the pipeline reference to prevent further use
            logger.debug("{} Request pipeline removed.", this);
            requestPipelineRef.compareAndSet(pipeline, null);
        }
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
        logger.debug("{} OnSubscribe invoked.", this);
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void clientEndStreamReceived() {
        logger.debug("{} Client End Stream received.", this);
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

        if (currentState() == ConnectionState.CLOSED) {
            logger.debug("{} onNext invoked but connection is already closed ({}).", this, response);
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
        } else if (response.hasNodeBehindPublisher()) {
            blockStreamMetrics.recordResponseReceived(response.response().kind());
            blockStreamMetrics.recordLatestBlockBehindPublisher(
                    response.nodeBehindPublisher().blockNumber());
            handleBlockNodeBehind(response.nodeBehindPublisher());
        } else {
            blockStreamMetrics.recordUnknownResponseReceived();
            logger.debug("{} Unexpected response received: {}.", this, response);
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
        if (!currentState().isTerminal()) {
            blockStreamMetrics.recordConnectionOnError();

            if (error instanceof final GrpcException grpcException) {
                logger.warn("{} Error received (grpcStatus={}).", this, grpcException.status(), grpcException);
            } else {
                logger.warn("{} Error received.", this, error);
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
        if (currentState() == ConnectionState.CLOSED) {
            logger.debug("{} onComplete invoked but connection is already closed.", this);
            return;
        }

        if (streamShutdownInProgress.getAndSet(false)) {
            logger.debug("{} Stream completed (stream close was in progress).", this);
        } else {
            logger.debug("{} Stream completed unexpectedly.", this);
            handleStreamFailure();
        }
    }

    /**
     * Indicates that this connection should be closed at the next block boundary. If this connection is actively
     * streaming a block, then the connection will wait until the block is fully sent before closing. If the connection
     * is waiting to stream a block that is not available, then the connection will be closed without sending any items
     * for the pending block.
     */
    public void closeAtBlockBoundary() {
        logger.info("{} Connection will be closed at the next block boundary", this);
        closeAtNextBlockBoundary.set(true);
    }

    /**
     * Worker that handles sending requests to the block node this connection is associated with.
     * <p>
     * This task/worker is based around an infinite loop that only exits when the connection state enters a terminal
     * state. Otherwise, each iteration of the loop will check to see if the block to stream has been changed (either
     * because all block items have been sent for the current block or a response from the block node indicates we need
     * to change blocks - e.g. SkipBlock response).
     * <p>
     * If there are block items available for the current streaming block, then they will be added to a "pending" block.
     * This pending request will be sent if any one of the following conditions are met:
     * <ul>
     *     <li>The request contains the block proof</li>
     *     <li>The next item to add to the request exceeds the maximum allowable request size</li>
     *     <li>The time since the last request was sent exceeds the maximum delay configured</li>
     * </ul>
     */
    private class ConnectionWorkerLoopTask implements Runnable {

        private final List<BlockItem> pendingRequestItems = new ArrayList<>();
        private long pendingRequestBytes;
        private int itemIndex = 0;
        private boolean pendingRequestHasBlockProof = false;
        private boolean pendingRequestHasBlockHeader = false;
        private BlockState block;
        private long lastSendTimeMillis = -1;
        private final AtomicInteger requestCtr = new AtomicInteger(1);
        private final long softLimitBytes;
        private final long hardLimitBytes;
        private final int requestBasePaddingBytes;
        private final int requestItemPaddingBytes;

        private ConnectionWorkerLoopTask() {
            softLimitBytes = configuration().messageSizeSoftLimitBytes();
            hardLimitBytes = configuration().messageSizeHardLimitBytes();
            requestBasePaddingBytes = requestPaddingBytes();
            requestItemPaddingBytes = requestItemPaddingBytes();

            pendingRequestBytes = requestBasePaddingBytes;
        }

        @Override
        public void run() {
            logger.info(
                    "{} Worker thread started (messageSizeSoftLimit={}, messageSizeHardLimit={}, requestPadding={}, itemPadding={})",
                    BlockNodeStreamingConnection.this,
                    softLimitBytes,
                    hardLimitBytes,
                    requestBasePaddingBytes,
                    requestItemPaddingBytes);

            while (true) {
                try {
                    if (currentState().isTerminal()) {
                        break;
                    }

                    final boolean shouldSleep = doWork();

                    if (currentState().isTerminal()) {
                        // The connection is in a terminal state so allow the worker to stop
                        break;
                    }

                    if (shouldSleep) {
                        Thread.sleep(connectionWorkerSleepMillis());
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("{} Worker loop was interrupted", BlockNodeStreamingConnection.this);
                } catch (final Exception e) {
                    logger.warn("{} Error caught in connection worker loop", BlockNodeStreamingConnection.this, e);
                }
            }

            // if we exit the worker loop, then this thread is over... remove it from the worker thread reference
            logger.info("{} Worker thread exiting", BlockNodeStreamingConnection.this);
            workerThreadRef.compareAndSet(Thread.currentThread(), null);
        }

        /**
         * Main entry for the worker task. This will attempt to send blocks to a block node as well as additional
         * "housekeeping" like switching which block we actively stream.
         *
         * @return true if the worker should sleep before trying to do more work, else false if the worker should not
         * sleep and instead immediately try to do more work
         */
        private boolean doWork() {
            switchBlockIfNeeded();

            if (block == null) {
                // The block we want to stream is not available
                if (closeAtNextBlockBoundary.get()) {
                    // The flag to indicate that we should close the connection at a block boundary is set to true
                    // since no block is available to stream, we are at a safe "boundary" and can close the connection
                    logger.info(
                            "{} Block boundary reached; closing connection (no block available)",
                            BlockNodeStreamingConnection.this);
                    endTheStreamWith(EndStream.Code.RESET);
                }

                return true;
            }

            BlockItem item;

            while ((item = block.blockItem(itemIndex)) != null) {
                if (itemIndex == 0) {
                    logger.trace(
                            "{} Starting to process items for block {}",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber());
                    blockStreamMetrics.recordStreamingBlockNumber(block.blockNumber());
                    if (lastSendTimeMillis == -1) {
                        // if we've never sent a request and this is the first time we are processing a block, update
                        // the last send time to the current time. this will avoid prematurely sending a request
                        lastSendTimeMillis = System.currentTimeMillis();
                    }
                }

                final int itemSize = item.protobufSize() + requestItemPaddingBytes;
                final long newRequestBytes = pendingRequestBytes + itemSize;

                if (itemSize > hardLimitBytes) {
                    // the item exceeds the absolute max request size (even without accounting for request overhead)
                    // if there are any pending items, attempt to send them but regardless of the outcome we want to
                    // close the connection
                    try {
                        trySendPendingRequest();
                    } catch (final Exception e) {
                        // ignore exception... we are about to close the connection
                    }
                    blockStreamMetrics.recordRequestExceedsHardLimit();
                    logger.error(
                            "{} !!! FATAL: Block item exceeds max message size hard limit; closing connection (block={}, itemIndex={}, itemSize={}, sizeHardLimit={})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            itemIndex,
                            itemSize,
                            hardLimitBytes);
                    endTheStreamWith(EndStream.Code.ERROR);
                    return true;
                } else if (itemSize >= softLimitBytes) {
                    // the item is too large to fit into a normal request, so make it a part of its own request
                    // we want to send any previous pending items first though
                    if (!pendingRequestItems.isEmpty() && !trySendPendingRequest()) {
                        return true; // failed to send the request for some reason; exit early
                    }

                    // add the new large item to its own request and try to send it
                    pendingRequestItems.add(item);
                    pendingRequestBytes += itemSize;
                    pendingRequestHasBlockProof |= item.hasBlockProof();
                    pendingRequestHasBlockHeader |= item.hasBlockHeader();
                    ++itemIndex;

                    if (!trySendPendingRequest()) {
                        return true; // failed to send the request for some reason; exit early
                    }
                } else if (newRequestBytes > softLimitBytes) {
                    // if we add the item to the current request, the request would exceed the soft limit so send
                    // the pending request and start a new request with the item
                    if (!trySendPendingRequest()) {
                        return true; // failed to send the request for some reason; exit early
                    }
                } else {
                    // adding the item to the current pending item wouldn't exceed the soft limit so add it
                    pendingRequestItems.add(item);
                    pendingRequestBytes += itemSize;
                    pendingRequestHasBlockProof |= item.hasBlockProof();
                    pendingRequestHasBlockHeader |= item.hasBlockHeader();
                    ++itemIndex;
                }
            }

            maybeSendPendingRequest();
            maybeAdvanceBlock();

            /*
            Inform the worker thread to sleep if the current block isn't available (e.g. due to advancing blocks) or the
            number of items associated with this block is the same as the number of items we've collected thus far (i.e.
            the has caught up with all the items produced for the block.)
             */
            return block == null || block.itemCount() == itemIndex;
        }

        /**
         * Sends a request to the block node if there are any pending items ready to send and if one of two conditions
         * are met:
         * <ol>
         *     <li>The current block is closed AND all the block's items have been added to the pending request.</li>
         *     <li>The time between now and the last time a request was sent is equal to or greater than the max
         *         delay permitted.</li>
         * </ol>
         */
        private void maybeSendPendingRequest() {
            if (pendingRequestItems.isEmpty()) {
                return;
            }

            if (block.isClosed() && block.itemCount() == itemIndex) {
                // Send the last pending items of the block
                trySendPendingRequest();
            } else {
                // If the duration since the last time of sending a request exceeds the max delay configuration,
                // send the pending items
                final long diffMillis = System.currentTimeMillis() - lastSendTimeMillis;
                final long maxDelayMillis = maxRequestDelayMillis();
                if (diffMillis >= maxDelayMillis) {
                    logger.trace(
                            "{} Max delay exceeded (target: {}ms, actual: {}ms) - sending {} item(s)",
                            BlockNodeStreamingConnection.this,
                            maxDelayMillis,
                            diffMillis,
                            pendingRequestItems.size());
                    trySendPendingRequest();
                }
            }
        }

        /**
         * Sends the block end message to the block node in its own request.
         */
        private void sendBlockEnd() {
            final PublishStreamRequest endOfBlock = PublishStreamRequest.newBuilder()
                    .endOfBlock(BlockEnd.newBuilder().blockNumber(block.blockNumber()))
                    .build();
            try {
                if (sendRequest(new BlockEndRequest(endOfBlock, block.blockNumber(), requestCtr.get()))) {
                    blockStreamMetrics.recordLatestBlockEndOfBlockSent(block.blockNumber());
                    block.setBlockEndSentMs(System.currentTimeMillis());
                    if (block.getHeaderSentMs() != null) {
                        long latencyMs = block.getBlockEndSentMs() - block.getHeaderSentMs();
                        blockStreamMetrics.recordHeaderSentToBlockEndSentLatency(latencyMs);
                    }
                }
            } catch (final RuntimeException e) {
                logger.warn("{} Error sending EndOfBlock request", BlockNodeStreamingConnection.this, e);
                handleStreamFailureWithoutOnComplete();
            }
        }

        /**
         * Checks if the current block has all of its items sent. If so, then the BlockEnd is sent and the next block is
         * loaded into the worker. Alternatively, if there is a request to close the connection at a block boundary and
         * the current block is finished, then this connection will be closed.
         */
        private void maybeAdvanceBlock() {
            final boolean finishedWithCurrentBlock = pendingRequestItems.isEmpty() // no more items ready to send
                    && block.isClosed() // the block is closed, so no more items are expected
                    && block.itemCount() == itemIndex; // we've exhausted all items in the block

            if (!finishedWithCurrentBlock) {
                return; // still more work to do
            }

            // send the BlockEnd to the block node announcing we are complete with the block
            sendBlockEnd();

            /*
            We are now done with the current block and have two options:
            1) We advance to the next block (normal case).
            2) This connection has been marked for closure after we are finished processing the current block. If this
               is true, then we will close this connection. This allows us to close the connection at a block boundary
               instead of closing the connection mid-block.
             */

            if (closeAtNextBlockBoundary.get()) {
                // the connection manager wants us to gracefully stop this connection
                logger.info(
                        "{} Block boundary reached; closing connection (finished sending block)",
                        BlockNodeStreamingConnection.this);
                endTheStreamWith(EndStream.Code.RESET);
            } else {
                // the connection manager hasn't informed us to close this connection, so we are now free to advance to
                // the next block
                final long nextBlockNumber = block.blockNumber() + 1;
                if (streamingBlockNumber.compareAndSet(block.blockNumber(), nextBlockNumber)) {
                    logger.trace("{} Advancing to block {}", BlockNodeStreamingConnection.this, nextBlockNumber);
                } else {
                    logger.trace(
                            "{} Tried to advance to block {} but the block to stream was updated externally",
                            BlockNodeStreamingConnection.this,
                            nextBlockNumber);
                }

                // the block number to stream has changed, so swap the block
                switchBlockIfNeeded();
            }
        }

        /**
         * Attempt to send the pending request. If the request contains multiple items and the actual size exceeds the
         * max message size soft limit, then one or more items will be removed from the request to ensure the request
         * fits within the soft limit size. Requests that exceed the soft limit size, but are within the hard limit max
         * size should contain only the single large item.
         *
         * @return true if request was successfully sent, else false
         */
        private boolean trySendPendingRequest() {
            if (pendingRequestItems.isEmpty()) {
                // there are no items to send, consider this invocation successful and exit early
                return true;
            }

            final BlockItemSet itemSet = BlockItemSet.newBuilder()
                    .blockItems(List.copyOf(pendingRequestItems))
                    .build();
            final PublishStreamRequest req =
                    PublishStreamRequest.newBuilder().blockItems(itemSet).build();
            final long reqBytes = req.protobufSize();

            // now that we are able to build the real request we can finally determine the true size of the request
            // instead of just doing a best-guess estimate that we've been doing up until this point

            if (reqBytes > softLimitBytes && pendingRequestItems.size() > 1) {
                // the multi-item request exceeds the soft limit
                // try to remove the last item from the request and try sending again
                blockStreamMetrics.recordMultiItemRequestExceedsSoftLimit();
                logger.trace(
                        "{} Multi-item request exceeds soft limit; will attempt to remove last item and send again (requestSize={}, items={})",
                        BlockNodeStreamingConnection.this,
                        reqBytes,
                        pendingRequestItems.size());
                // remove the last item from the pending item set and update state to reflect the removal of the item
                final BlockItem item = pendingRequestItems.removeLast();
                --itemIndex;
                pendingRequestBytes -= (item.protobufSize() + requestItemPaddingBytes);
                if (item.hasBlockProof()) {
                    pendingRequestHasBlockProof = false;
                }
                if (item.hasBlockHeader()) {
                    pendingRequestHasBlockHeader = false;
                }
                return trySendPendingRequest();
            } else if (reqBytes > hardLimitBytes) {
                // the request exceeds the hard limit size... abandon all hope
                blockStreamMetrics.recordRequestExceedsHardLimit();
                logger.error(
                        "{} !!! FATAL: Request exceeds maximum size hard limit of {} bytes "
                                + "(block={}, requestSize={}); Closing connection",
                        BlockNodeStreamingConnection.this,
                        hardLimitBytes,
                        block.blockNumber(),
                        reqBytes);
                endTheStreamWith(EndStream.Code.ERROR);
                return false;
            }

            logger.trace(
                    "{} Attempting to send request (block={}, request={}, itemCount={}, bytes={})",
                    BlockNodeStreamingConnection.this,
                    block.blockNumber(),
                    requestCtr.get(),
                    pendingRequestItems.size(),
                    reqBytes);

            try {
                if (sendRequest(new BlockItemsStreamRequest(
                        req,
                        block.blockNumber(),
                        requestCtr.get(),
                        pendingRequestItems.size(),
                        pendingRequestHasBlockProof,
                        pendingRequestHasBlockHeader))) {
                    // record that we've sent the request
                    lastSendTimeMillis = System.currentTimeMillis();

                    // clear the pending request data
                    pendingRequestBytes = requestBasePaddingBytes;
                    pendingRequestItems.clear();
                    requestCtr.incrementAndGet();
                    pendingRequestHasBlockProof = false;
                    pendingRequestHasBlockHeader = false;
                    return true;
                } else {
                    logger.warn(
                            "{} Sending the request failed for a non-exceptional reason (block={}, request={})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get());
                }
            } catch (final UncheckedIOException e) {
                logger.warn(
                        "{} UncheckedIOException caught in connection worker thread (block={}, request={})",
                        BlockNodeStreamingConnection.this,
                        block.blockNumber(),
                        requestCtr.get(),
                        e);
                handleStreamFailureWithoutOnComplete();
            } catch (final Exception e) {
                logger.warn(
                        "{} Exception caught in connection worker thread (block={}, request={})",
                        BlockNodeStreamingConnection.this,
                        block.blockNumber(),
                        requestCtr.get(),
                        e);
                handleStreamFailure();
            }

            return false;
        }

        /**
         * Switches the active block if the connection's specified active block is different from the most recently
         * used block. This will also determine which block to initialize with.
         */
        private void switchBlockIfNeeded() {
            if (streamingBlockNumber.get() == -1) {
                final long latestBlock = blockBufferService.getLastBlockNumberProduced();

                if (latestBlock != -1 && streamingBlockNumber.compareAndSet(-1L, latestBlock)) {
                    logger.info(
                            "{} Connection was not initialized with a starting block; defaulting to latest block produced ({})",
                            BlockNodeStreamingConnection.this,
                            latestBlock);
                }
            }

            final long latestActiveBlockNumber = streamingBlockNumber.get();
            if (latestActiveBlockNumber == -1) {
                return; // No blocks available to stream
            }

            if (block != null && block.blockNumber() == latestActiveBlockNumber) {
                // The block hasn't changed so we can exit
                return;
            }

            // Swap blocks and reset
            final BlockState oldBlock = block;
            block = blockBufferService.getBlockState(latestActiveBlockNumber);

            if (block == null && latestActiveBlockNumber < blockBufferService.getEarliestAvailableBlockNumber()) {
                // Indicate that the block node should catch up from another trustworthy block node
                logger.warn(
                        "{} Wanted block ({}) is not obtainable; notifying block node it is too far behind and closing connection",
                        BlockNodeStreamingConnection.this,
                        latestActiveBlockNumber);
                endStreamAndReschedule(TOO_FAR_BEHIND);
            }

            pendingRequestBytes = requestBasePaddingBytes;
            itemIndex = 0;
            pendingRequestItems.clear();
            requestCtr.set(1);
            pendingRequestHasBlockProof = false;
            pendingRequestHasBlockHeader = false;

            if (block == null) {
                logger.trace(
                        "{} Wanted to switch from block {} to block {}, but it is not available",
                        BlockNodeStreamingConnection.this,
                        (oldBlock == null ? -1 : oldBlock.blockNumber()),
                        latestActiveBlockNumber);
            } else {
                logger.trace(
                        "{} Switched from block {} to block {}",
                        BlockNodeStreamingConnection.this,
                        (oldBlock == null ? -1 : oldBlock.blockNumber()),
                        latestActiveBlockNumber);
            }
        }

        /**
         * @return the maximum amount of time (in milliseconds) between sending requests to a block node
         */
        private long maxRequestDelayMillis() {
            return configProvider()
                    .getConfiguration()
                    .getConfigData(BlockNodeConnectionConfig.class)
                    .maxRequestDelay()
                    .toMillis();
        }

        /**
         * @return the amount of time (in milliseconds) to sleep between connection worker loop iterations
         */
        private long connectionWorkerSleepMillis() {
            return configProvider()
                    .getConfiguration()
                    .getConfigData(BlockNodeConnectionConfig.class)
                    .connectionWorkerSleepDuration()
                    .toMillis();
        }

        /**
         * @return the base number of bytes per request when estimating the total size of a given request
         */
        private int requestPaddingBytes() {
            return configProvider()
                    .getConfiguration()
                    .getConfigData(BlockNodeConnectionConfig.class)
                    .streamingRequestPaddingBytes();
        }

        /**
         * @return the number of bytes to add per block item when estimating the total size of a given request
         */
        private int requestItemPaddingBytes() {
            return configProvider()
                    .getConfiguration()
                    .getConfigData(BlockNodeConnectionConfig.class)
                    .streamingRequestItemPaddingBytes();
        }
    }

    /**
     * Wrapper interface for a PublishStreamRequest.
     */
    sealed interface StreamRequest permits EndStreamRequest, BlockRequest {

        /**
         * @return the PublishStreamRequest to send
         */
        @NonNull
        PublishStreamRequest streamRequest();

        /**
         * @return the type of PublishStreamRequest
         */
        default PublishStreamRequest.RequestOneOfType streamRequestType() {
            return streamRequest().request().kind();
        }
    }

    /**
     * PublishStreamRequest that is specific to a single block.
     */
    sealed interface BlockRequest extends StreamRequest permits BlockEndRequest, BlockItemsStreamRequest {

        /**
         * @return the block number associated with this request
         */
        long blockNumber();

        /**
         * @return the request number
         */
        int requestNumber();
    }

    /**
     * A PublishStreamRequest of type EndStream.
     *
     * @param streamRequest the PublishStreamRequest to send
     */
    record EndStreamRequest(@NonNull PublishStreamRequest streamRequest) implements StreamRequest {
        EndStreamRequest {
            requireNonNull(streamRequest);
        }

        /**
         * @return the EndStream.Code associated eith the request
         */
        @NonNull
        EndStream.Code code() {
            return requireNonNull(streamRequest().endStream()).endCode();
        }
    }

    /**
     * A PublishStreamRequest of type BlockEnd.
     *
     * @param streamRequest the PublishStreamRequest to send
     * @param blockNumber the block number associated with the BlockEnd request
     * @param requestNumber the request number
     */
    record BlockEndRequest(@NonNull PublishStreamRequest streamRequest, long blockNumber, int requestNumber)
            implements BlockRequest {
        BlockEndRequest {
            requireNonNull(streamRequest);
        }
    }

    /**
     * A PublishStreamRequest of type BlockItems.
     *
     * @param streamRequest the PublishStreamRequest to send
     * @param blockNumber the block number associated with the BlockItems request
     * @param requestNumber the request number
     * @param numItems the number of items in the request
     * @param hasBlockProof true if the request contains the block proof, else false
     */
    record BlockItemsStreamRequest(
            @NonNull PublishStreamRequest streamRequest,
            long blockNumber,
            int requestNumber,
            int numItems,
            boolean hasBlockProof,
            boolean hasBlockHeader)
            implements BlockRequest {
        BlockItemsStreamRequest {
            requireNonNull(streamRequest);
        }
    }
}
