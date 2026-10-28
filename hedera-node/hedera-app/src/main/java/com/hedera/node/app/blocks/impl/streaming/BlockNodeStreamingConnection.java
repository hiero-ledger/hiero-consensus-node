// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.BlockItemSetBytes;
import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeStats.HighLatencyResult;
import com.hedera.node.app.blocks.impl.streaming.BlockState.BufferedItem;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.obs.BlockStreamingObs;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockEnd;
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
 *   <li>Handles the streaming of block items to a configured Block Node</li>
 *   <li>Maintains connection state and handles responses from the Block Node</li>
 *   <li>Coordinates with {@link BlockNodeConnectionManager} for managing the connection lifecycle</li>
 *   <li>Processes block acknowledgements, retries, and error scenarios</li>
 * </ul>
 * <p>
 * The connection goes through multiple states defined in {@link ConnectionState} and
 * uses exponential backoff for retries when errors occur.
 */
public class BlockNodeStreamingConnection extends BlockNodePublisherConnection {

    private static final Logger logger = LogManager.getLogger(BlockNodeStreamingConnection.class);

    /**
     * Manager that maintains the system-wide state as it pertains to block streaming. Access here is used to retrieve
     * blocks for streaming and indicating which blocks have been acknowledged by the block node.
     */
    private final BlockBufferService blockBufferService;
    /**
     * The reset period for the stream. This is used to periodically reset the stream to ensure increased stability and reliability.
     */
    private final Duration streamResetPeriod;
    /**
     * The maximum jitter applied to the stream reset period scheduling to avoid thundering herd.
     */
    private final Duration streamResetPeriodJitter;
    /**
     * The current block number being streamed.
     */
    private final AtomicLong streamingBlockNumber = new AtomicLong(-1);
    /**
     * Reference to the worker thread, once it is initialized.
     */
    private final AtomicReference<Thread> workerThreadRef = new AtomicReference<>();
    /**
     * Flag indicating if this connection should be closed at the next block boundary. For example: if set to true while
     * the connection is actively streaming a block, then the connection will continue to stream the remaining block and
     * once it is finished it will close the connection.
     */
    private final AtomicBoolean closeAtNextBlockBoundary = new AtomicBoolean(false);
    /**
     * Reference to the reason why this connection is being closed. This is mainly used to track the close reason when
     * the connection is intending to close at some time in the future - e.g. at the next close boundary.
     */
    private final AtomicReference<CloseReason> pendingCloseReason = new AtomicReference<>();
    /**
     * The timestamp of when this connection should be auto reset - the "periodic" reset interval.
     */
    private final Instant autoResetTimestamp;
    /**
     * Service for tracking enhanced observability data related to block streaming.
     */
    private final BlockStreamingObs streamingObs;

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param configProvider the configuration to use
     * @param connectionManager the connection manager coordinating block node connections
     * @param blockBufferService the block stream state manager for block node connections
     * @param blockStreamMetrics the block stream metrics for block node connections
     * @param blockingIoExecutor the executor service used for blocking I/O operations (e.g. sending a message)
     * @param initialBlockToStream the initial block number to start streaming from, or null to use default
     * @param clientFactory the factory for creating block stream clients
     * @param nodeId the id of the node owning this connection
     */
    public BlockNodeStreamingConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNode blockNode,
            @NonNull final BlockNodeConnectionManager connectionManager,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamMetrics blockStreamMetrics,
            @NonNull final ExecutorService blockingIoExecutor,
            @Nullable final Long initialBlockToStream,
            @NonNull final BlockNodeClientFactory clientFactory,
            final long nodeId,
            @NonNull final BlockStreamingObs streamingObs) {
        super(
                configProvider,
                blockNode,
                ConnectionType.BLOCK_STREAMING,
                clientFactory,
                nodeId,
                blockStreamMetrics,
                blockingIoExecutor,
                blockBufferService,
                connectionManager);
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
        this.streamingObs = requireNonNull(streamingObs);
        this.streamResetPeriod = bncConfig().streamResetPeriod();
        this.streamResetPeriodJitter = bncConfig().streamResetPeriodJitter();

        if (initialBlockToStream != null && initialBlockToStream != -1) {
            streamingBlockNumber.set(initialBlockToStream);
            logger.info(
                    "{} Block node connection will initially stream with block {}",
                    BlockNodeStreamingConnection.this,
                    initialBlockToStream);
        }

        autoResetTimestamp = calculateAutoResetTimestamp();
    }

    /**
     * @return the timestamp representing when this connection should be auto reset
     */
    @NonNull
    Instant autoResetTimestamp() {
        return autoResetTimestamp;
    }

    @Override
    void onActiveStateTransition() {
        if (!isRequestClientActive()) {
            logger.warn(
                    "{} Connection transitioned to ACTIVE but the request pipeline has not been established; closing connection",
                    this);
            close(CloseReason.INTERNAL_ERROR, false, EndStream.Code.ERROR);
            return;
        }

        // start worker thread to handle sending requests
        final Thread workerThread = new Thread(new ConnectionWorkerLoopTask(), "bn-conn-worker-" + connectionId());
        if (workerThreadRef.compareAndSet(null, workerThread)) {
            workerThread.setDaemon(true);
            workerThread.start();
        }

        connectionManager().notifyConnectionActive(this);
    }

    @Override
    void postSuccessfulRequestSend(@NonNull final PublisherRequest request, @NonNull final SendRequestResult result) {
        if (request instanceof final PublisherBlockItemsRequest pbir) {
            if (pbir.hasBlockProof() && result.context().successfulSentTimestamp() != null) {
                blockNode()
                        .stats()
                        .recordBlockProofSent(
                                pbir.blockNumber(), result.context().successfulSentTimestamp());
            }
            if (pbir.hasBlockHeader()) {
                final BlockState block = blockBufferService.getBlockState(pbir.blockNumber());
                if (block != null) {
                    block.setHeaderSentNanos(result.context().endNanos());
                }
            }
        }
    }

    private Instant calculateAutoResetTimestamp() {
        final long baseDelayMs = streamResetPeriod.toMillis();
        final long maxJitterMs = streamResetPeriodJitter.toMillis();

        if (maxJitterMs > 0) {
            if (maxJitterMs >= baseDelayMs) {
                logger.warn(
                        "{} Max auto stream reset jitter ({}ms) must be less than the base stream reset period ({}ms); ignoring jitter",
                        this,
                        maxJitterMs,
                        baseDelayMs);
            } else {
                final long jitterMs = ThreadLocalRandom.current().nextLong(maxJitterMs);
                return Instant.now().plusMillis(baseDelayMs - jitterMs);
            }
        }

        return Instant.now().plusMillis(baseDelayMs);
    }

    /**
     * Handles the {@link BlockAcknowledgement} response received from the block node.
     *
     * @param acknowledgement the acknowledgement received from the block node
     */
    private void handleAcknowledgement(@NonNull final BlockAcknowledgement acknowledgement) {
        final long acknowledgedBlockNumber = acknowledgement.blockNumber();
        logger.debug("{} BlockAcknowledgement received for block {}", this, acknowledgedBlockNumber);
        acknowledgeBlocks(acknowledgedBlockNumber, true);

        // Evaluate latency and high-latency QoS via the connection manager
        final Duration highLatencyThreshold = bncConfig().highLatencyThreshold();
        final int maxHighLatencyEventsAllowed = bncConfig().highLatencyEventsBeforeSwitching();

        final HighLatencyResult result = blockNode()
                .stats()
                .recordAcknowledgementAndEvaluate(
                        acknowledgedBlockNumber, Instant.now(), highLatencyThreshold, maxHighLatencyEventsAllowed);

        if (result.isHighLatency()) {
            logger.debug(
                    "{} A high latency event ({}ms) has been detected (consecutive events: {})",
                    this,
                    result.latencyMs(),
                    result.consecutiveHighLatencyEvents());
            metrics().recordHighLatencyEvent();
        }

        if (result.shouldSwitch()) {
            logger.info(
                    "{} Block node has exceeded high latency threshold {} times consecutively; closing connection",
                    this,
                    result.consecutiveHighLatencyEvents());
            close(CloseReason.BLOCK_NODE_HIGH_LATENCY, true, EndStream.Code.TIMEOUT);
        }
    }

    private void updateAcknowledgementMetrics(
            final long previousAcknowledgedBlockNumber, final long latestAcknowledgedBlockNumber) {
        if (latestAcknowledgedBlockNumber == Long.MAX_VALUE
                || previousAcknowledgedBlockNumber == latestAcknowledgedBlockNumber) {
            return;
        }

        final long currentBlockProducing = blockBufferService.getLastBlockNumberProduced();
        final long lowestAvailableBlockInBuffer = blockBufferService.getEarliestAvailableBlockNumber();
        final long nowNanos = System.nanoTime();
        final long start = Math.max(previousAcknowledgedBlockNumber + 1, lowestAvailableBlockInBuffer);
        final long end = Math.min(latestAcknowledgedBlockNumber, currentBlockProducing);

        if (start <= end) {
            for (long blkNum = start; blkNum <= end; blkNum++) {
                final BlockState blockState = blockBufferService.getBlockState(blkNum);
                if (blockState != null) {
                    final long openedNanos = blockState.openedNanos();
                    if (openedNanos != -1) {
                        metrics().recordHeaderProducedToAckLatency(durationMicros(openedNanos, nowNanos));
                    }
                    final long closedNanos = blockState.closedNanos();
                    if (closedNanos != -1) {
                        metrics().recordBlockClosedToAckLatency(durationMicros(closedNanos, nowNanos));
                    }
                    final long headerSentNanos = blockState.headerSentNanos();
                    if (headerSentNanos != -1) {
                        metrics().recordHeaderSentAckLatency(durationMicros(headerSentNanos, nowNanos));
                    }
                    final long blockEndSentNanos = blockState.blockEndSentNanos();
                    if (blockEndSentNanos != -1) {
                        metrics().recordBlockEndSentToAckLatency(durationMicros(blockEndSentNanos, nowNanos));
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
        final long currentBlockStreaming = streamingBlockNumber.get();
        final long currentBlockProducing = blockBufferService.getLastBlockNumberProduced();
        final long previousAcknowledgedBlockNumber = blockBufferService.getHighestAckedBlockNumber();

        /*
        Record the acknowledgement in the block buffer. Depending on the minimum number of required acknowledgements,
        this acknowledgement received may or may not actually cause the block to be fully acknowledged. Regardless of
        this outcome, we want to treat the acknowledgement received over this connection as the decision maker for
        whether we should jump ahead, and not use the real acknowledgement level tracked by the block buffer.
         */
        blockBufferService.acknowledgeBlock(
                acknowledgedBlockNumber, blockNode().configuration().streamingEndpoint());

        final long latestAcknowledgedBlockNumber = blockBufferService.getHighestAckedBlockNumber();

        if (previousAcknowledgedBlockNumber != latestAcknowledgedBlockNumber) {
            // this acknowledgement raised the acknowledgement high watermark, thus publish metrics recording it
            updateAcknowledgementMetrics(previousAcknowledgedBlockNumber, latestAcknowledgedBlockNumber);
        }

        connectionStatistics().recordAcknowledgement(acknowledgedBlockNumber);

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
                    "{} Received acknowledgement for block {}, later than current streamed ({}) or produced ({})",
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
        final long lastVerifiedBlock = endOfStream.blockNumber();
        final long approxProblematicBlock = endOfStream.proximateBlockNumber();
        final EndOfStream.Code responseCode = endOfStream.status();

        logger.info(
                "{} Received EndOfStream response (lastVerifiedBlock: {}, approxProblematicBlock: {}, responseCode: {})",
                this,
                lastVerifiedBlock,
                approxProblematicBlock,
                responseCode);

        // Update the latest acknowledged block number
        acknowledgeBlocks(lastVerifiedBlock, false);

        // Check if we've exceeded the EndOfStream rate limit
        // Record the EndOfStream event and check if the rate limit has been exceeded.
        // The connection manager maintains persistent stats for each node across connections.
        final int maxEndStreamsAllowed = bncConfig().maxEndOfStreamsAllowed();
        final Duration eosTimeframe = bncConfig().endOfStreamTimeFrame();

        if (blockNode().stats().addEndOfStreamAndCheckLimit(Instant.now(), maxEndStreamsAllowed, eosTimeframe)) {
            logger.info(
                    "{} Block node has exceeded the number of allowed EndOfStream responses (received: {}, permitted: {}, timeWindow: {})",
                    this,
                    blockNode().stats().getEndOfStreamCount(),
                    maxEndStreamsAllowed,
                    eosTimeframe);

            metrics().recordEndOfStreamLimitExceeded();

            // We received an EndStream from the block node, so when we close this connection don't try to send our own
            // EndStream to the block node since the connection is probably already torn down
            close(CloseReason.TOO_MANY_END_STREAM_RESPONSES, true, null);
            return;
        }

        switch (responseCode) {
            case Code.TIMEOUT, Code.DUPLICATE_BLOCK, Code.BAD_BLOCK_PROOF, Code.INVALID_REQUEST -> {
                // A transient issue happened on the BN, so still close the connection but don't force the block node
                // to enter a cool-down period.
                close(CloseReason.TRANSIENT_END_STREAM_RECEIVED, true, null);
            }
            default -> {
                // A non-transient close reason was received, so close the connection and let the block node go into
                // a cool-down period
                close(CloseReason.END_STREAM_RECEIVED, true, null);
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
                "{} Received SkipBlock response (blockToSkip: {}), but we've moved on to another block - ignoring skip request",
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
        logger.debug("{} Received ResendBlock response for block {}", this, resendBlockNumber);

        if (blockBufferService.getBlockState(resendBlockNumber) != null) {
            streamingBlockNumber.set(resendBlockNumber);
        } else {
            // If we don't have the block state, we schedule retry for this connection and establish new one
            // with different block node
            logger.info(
                    "{} Block node requested a ResendBlock for block {} but that block does not exist; closing connection",
                    this,
                    resendBlockNumber);

            if (resendBlockNumber < blockBufferService.getEarliestAvailableBlockNumber()) {
                // Indicate that the block node should catch up from another trustworthy block node
                close(CloseReason.BLOCK_NODE_BEHIND, true, EndStream.Code.TOO_FAR_BEHIND);
            } else {
                close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
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
        final Instant now = Instant.now();
        final int maxBehindsAllowed = bncConfig().maxBehindPublishersAllowed();
        final Duration behindTimeframe = bncConfig().behindPublisherTimeFrame();
        final Duration ignorePeriod = bncConfig().behindPublisherIgnorePeriod();

        // Check if we're within the ignore period (resets in new time window)
        if (blockNode().stats().shouldIgnoreBehindPublisher(now, ignorePeriod, behindTimeframe)) {
            logger.info("{} Ignoring BehindPublisher response for block {} - within ignore period", this, blockNumber);
            return;
        }

        logger.info("{} Received BehindPublisher response for block {}", this, blockNumber);

        // Record the BehindPublisher event and check if the limit has been exceeded.
        if (blockNode().stats().addBehindPublisherAndCheckLimit(now, maxBehindsAllowed, behindTimeframe)) {
            logger.info(
                    "{} Block node has exceeded the allowed number of BehindPublisher responses "
                            + "(received: {}, permitted: {}, timeWindow: {})",
                    this,
                    blockNode().stats().getBehindPublisherCount(),
                    maxBehindsAllowed,
                    behindTimeframe);

            close(CloseReason.BLOCK_NODE_BEHIND, true, EndStream.Code.RESET);
            return;
        }

        final long blockToStream = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
        // The block node is behind us, check if we have the last verified block still available
        // to start streaming from there
        if (blockBufferService.getBlockState(blockToStream) != null) {
            logger.info("{} Block node reported it is behind; will start streaming block {}", this, blockToStream);

            streamingBlockNumber.set(blockToStream);
        } else {
            // If we don't have the block state, we schedule retry for this connection
            // and establish new one with different block node
            logger.info(
                    "{} Block node is behind and requested block ({}) is not available; ending stream",
                    this,
                    blockToStream);

            if (blockToStream < blockBufferService.getEarliestAvailableBlockNumber()) {
                // Indicate that the block node should catch up from another trustworthy block node
                close(CloseReason.BLOCK_NODE_BEHIND, true, EndStream.Code.TOO_FAR_BEHIND);
            } else {
                close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
            }
        }
    }

    private static long durationMicros(final long startNanos, final long endNanos) {
        return (endNanos - startNanos) / 1_000;
    }

    @Override
    void postClose() {
        logger.info(
                "{} Streaming connection closed (numBlocksSent: {}, lastBlockSent: {}, numBlocksAcked: {}, lastBlockAcked: {}, reqSendAttempts: {}, reqSendSuccesses: {})",
                this,
                connectionStatistics().numBlocksSent(),
                connectionStatistics().lastBlockSent(),
                connectionStatistics().numBlocksAcked(),
                connectionStatistics().lastBlockAcked(),
                connectionStatistics().numRequestSendAttempts(),
                connectionStatistics().numRequestSendSuccesses());
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
            logger.debug(
                    "{} Received response from block node, but this connection is closed (response: {})",
                    this,
                    response);
            return;
        }

        // Process the response
        if (response.hasAcknowledgement()) {
            metrics().recordResponseReceived(response.response().kind());
            handleAcknowledgement(response.acknowledgement());
        } else if (response.hasEndStream()) {
            metrics().recordResponseEndOfStreamReceived(response.endStream().status());
            metrics().recordLatestBlockEndOfStream(response.endStream().blockNumber());
            handleEndOfStream(response.endStream());
        } else if (response.hasSkipBlock()) {
            metrics().recordResponseReceived(response.response().kind());
            metrics().recordLatestBlockSkipBlock(response.skipBlock().blockNumber());
            handleSkipBlock(response.skipBlock());
        } else if (response.hasResendBlock()) {
            metrics().recordResponseReceived(response.response().kind());
            metrics().recordLatestBlockResendBlock(response.resendBlock().blockNumber());
            handleResendBlock(response.resendBlock());
        } else if (response.hasNodeBehindPublisher()) {
            metrics().recordResponseReceived(response.response().kind());
            metrics()
                    .recordLatestBlockBehindPublisher(
                            response.nodeBehindPublisher().blockNumber());
            handleBlockNodeBehind(response.nodeBehindPublisher());
        } else {
            metrics().recordUnknownResponseReceived();
            logger.warn("{} Unexpected response received: {}", this, response);
        }
    }

    /**
     * Indicates that this connection should be closed at the next block boundary. If this connection is actively
     * streaming a block, then the connection will wait until the block is fully sent before closing. If the connection
     * is waiting to stream a block that is not available, then the connection will be closed without sending any items
     * for the pending block.
     *
     * @param closeReason the reason why this connection is being closed (for observability)
     */
    public void closeAtBlockBoundary(@NonNull final CloseReason closeReason) {
        requireNonNull(closeReason, "Close reason is required");

        if (closeAtNextBlockBoundary.get()) {
            return; // we are already closing the connection so don't override the previous close reason
        }

        logger.info("{} Connection will be closed at the next block boundary (reason: {})", this, closeReason);
        closeAtNextBlockBoundary.set(true);
        pendingCloseReason.set(closeReason);
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

        private final List<BufferedItem> pendingRequestItems = new ArrayList<>();
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
        private int blockAttemptNumber = 1;

        private ConnectionWorkerLoopTask() {
            softLimitBytes = configuration().messageSizeSoftLimitBytes();
            hardLimitBytes = configuration().messageSizeHardLimitBytes();
            requestBasePaddingBytes = bncConfig().streamingRequestPaddingBytes();
            requestItemPaddingBytes = bncConfig().streamingRequestItemPaddingBytes();

            pendingRequestBytes = requestBasePaddingBytes;
        }

        @Override
        public void run() {
            logger.info(
                    "{} Worker thread started (messageSizeSoftLimit: {}, messageSizeHardLimit: {}, requestPadding: {}, itemPadding: {})",
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
                        final long sleepMillis =
                                bncConfig().connectionWorkerSleepDuration().toMillis();
                        Thread.sleep(sleepMillis);
                    }
                } catch (final InterruptedException _) {
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
            connectionStatistics().recordHeartbeat(System.currentTimeMillis());

            switchBlockIfNeeded();

            if (block == null) {
                // The block we want to stream is not available
                if (closeAtNextBlockBoundary.get()) {
                    // The flag to indicate that we should close the connection at a block boundary is set to true
                    // since no block is available to stream, we are at a safe "boundary" and can close the connection
                    logger.info(
                            "{} Block boundary reached; closing connection (no block available)",
                            BlockNodeStreamingConnection.this);
                    final CloseReason closeReason = pendingCloseReason.get();
                    close(closeReason != null ? closeReason : CloseReason.UNKNOWN, true, EndStream.Code.RESET);
                }

                return true;
            }

            BufferedItem item;

            while ((item = block.bufferedItem(itemIndex)) != null) {
                final long millisTimestamp = System.currentTimeMillis();
                connectionStatistics().recordHeartbeat(millisTimestamp);

                if (itemIndex == 0) {
                    logger.trace(
                            "{} Starting to process items for block {}",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber());
                    metrics().recordStreamingBlockNumber(block.blockNumber());
                    if (lastSendTimeMillis == -1) {
                        // if we've never sent a request and this is the first time we are processing a block, update
                        // the last send time to the current time. this will avoid prematurely sending a request
                        lastSendTimeMillis = millisTimestamp;
                    }
                }

                final int itemSize = item.size() + requestItemPaddingBytes;
                final long newRequestBytes = pendingRequestBytes + itemSize;

                if (logger.isTraceEnabled()) {
                    logger.trace(
                            "{} Trying to add item to pending request (block: {}, itemIndex: {}, itemBytes: {}, itemType: {}, pendingItems: {}->{}, estimatedPendingBytes: {}->{})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            itemIndex,
                            itemSize,
                            item.itemType(),
                            pendingRequestItems.size(),
                            pendingRequestItems.size() + 1,
                            pendingRequestBytes,
                            newRequestBytes);
                }

                if (itemSize > hardLimitBytes) {
                    // the item exceeds the absolute max request size (even without accounting for request overhead)
                    // if there are any pending items, attempt to send them but regardless of the outcome we want to
                    // ultimately close the connection
                    if (!pendingRequestItems.isEmpty()) {
                        logger.trace(
                                "{} Block item exceeds the max message size hard limit; attempting to send any preceding items (largeItemIndex: {})",
                                BlockNodeStreamingConnection.this,
                                item.index());
                        if (trySendPendingRequest() && itemIndex != item.index()) {
                            // we've successfully sent some pending items, but there are still more to send so try again
                            // before closing the connection
                            continue;
                        }
                    }
                    metrics().recordRequestExceedsHardLimit();
                    logger.error(
                            "{} !!! FATAL: Block item exceeds max message size hard limit; closing connection (block: {}, itemIndex: {}, itemSize: {} bytes, itemType: {}, sizeHardLimit: {} bytes)",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            itemIndex,
                            itemSize,
                            item.itemType(),
                            hardLimitBytes);
                    close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
                    return true;
                } else if (newRequestBytes >= softLimitBytes) {
                    if (logger.isTraceEnabled()) {
                        logger.trace(
                                "{} Item (block: {}, index: {}, type: {}, bytes: {}) will cause current pending request to exceed soft limit; it will be sent in another request (expectedBytes: {}, softLimitBytes: {})",
                                BlockNodeStreamingConnection.this,
                                block.blockNumber(),
                                itemIndex,
                                item.itemType(),
                                itemSize,
                                newRequestBytes,
                                softLimitBytes);
                    }
                    // the item is too large to fit into a normal request, so make it a part of its own request
                    // we want to send any previous pending items first though
                    if (!pendingRequestItems.isEmpty() && !trySendPendingRequest()) {
                        return true; // failed to send the request for some reason; exit early
                    }

                    if (itemIndex == item.index()) {
                        /*
                        trySendPendingRequest() may rollback the items added to the pending request if the actual
                        request exceeds our limits. When this happens, the current item index is decremented one or more
                        times. Thus, we should only add the current item to the next pending request if, and only if,
                        the current item index is the same as the item we originally tried to add. If they don't match,
                        we don't want to add the item and instead reenter the main loop and let it add the correct
                        item(s) to maintain proper order.
                         */
                        appendItem(item, itemSize);
                    }
                } else {
                    // adding the item to the current pending item wouldn't exceed the soft limit so add it
                    appendItem(item, itemSize);
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
         * Append the specified item to the pending request.
         *
         * @param item the item to append
         * @param itemSize the estimated size (in bytes) of the item being appended
         */
        private void appendItem(final BufferedItem item, final long itemSize) {
            pendingRequestItems.add(item);
            pendingRequestBytes += itemSize;
            pendingRequestHasBlockProof |= item.isProof();
            pendingRequestHasBlockHeader |= item.isHeader();
            ++itemIndex;
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
                final long maxDelayMillis = bncConfig().maxRequestDelay().toMillis();
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
         *
         * @return true if sending the block end was successful, else false
         */
        private boolean sendBlockEnd() {
            final PublishStreamRequestBytes endOfBlock = PublishStreamRequestBytes.newBuilder()
                    .endOfBlock(BlockEnd.newBuilder().blockNumber(block.blockNumber()))
                    .build();
            final long timeoutMillis = calculateRequestTimeout(endOfBlock.protobufSize());
            try {
                final SendRequestResult result = sendRequest(new PublisherBlockEndRequest(
                        endOfBlock, block.blockNumber(), requestCtr.get(), timeoutMillis, blockAttemptNumber));
                if (SendRequestStatus.SUCCESS == result.status()) {
                    streamingObs.onBlockEndSend(
                            block.blockNumber(),
                            result.context().startNanos(),
                            result.context().endNanos());
                    connectionStatistics().recordBlockSent(block.blockNumber());
                    metrics().recordLatestBlockEndOfBlockSent(block.blockNumber());
                    final long blockEndSentNanos = System.nanoTime();
                    block.setBlockEndSentNanos(blockEndSentNanos);
                    final long headerSentNanos = block.headerSentNanos();
                    if (headerSentNanos != -1) {
                        metrics()
                                .recordHeaderSentToBlockEndSentLatency(
                                        durationMicros(headerSentNanos, blockEndSentNanos));
                    }

                    return true;
                } else if (SendRequestStatus.TIMEOUT == result.status()) {
                    logger.warn(
                            "{} Timed out sending block end request; resetting to block start for retry (block: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber());
                    resetForBlockStart(true);
                } else {
                    logger.warn(
                            "{} Sending the block end request failed for a non-exceptional reason (block: {}, request: {}, reason: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get(),
                            result.status());
                }
            } catch (final RuntimeException e) {
                logger.warn("{} Error sending EndOfBlock request", BlockNodeStreamingConnection.this, e);
                close(CloseReason.CONNECTION_ERROR, false, EndStream.Code.ERROR);
            }

            return false;
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
            if (!sendBlockEnd()) {
                return;
            }

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
                final CloseReason closeReason = pendingCloseReason.get();
                close(closeReason != null ? closeReason : CloseReason.UNKNOWN, true, EndStream.Code.RESET);
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

            connectionStatistics().recordHeartbeat(System.currentTimeMillis());
            final BlockItemSetBytes itemSet = BlockItemSetBytes.newBuilder()
                    .blockItems(pendingRequestItems.stream()
                            .map(BufferedItem::serializedItem)
                            .toList())
                    .build();
            final PublishStreamRequestBytes req =
                    PublishStreamRequestBytes.newBuilder().blockItems(itemSet).build();
            final long reqBytes = req.protobufSize();

            // now that we are able to build the real request we can finally determine the true size of the request
            // instead of just doing a best-guess estimate that we've been doing up until this point

            if (logger.isTraceEnabled()) {
                final StringJoiner joiner = new StringJoiner(",");
                pendingRequestItems.forEach(item -> joiner.add(Integer.toString(item.index())));
                logger.trace(
                        "{} Wanting to send pending items (block: {}, items: {}=[{}], reqBytes: {})",
                        BlockNodeStreamingConnection.this,
                        block.blockNumber(),
                        pendingRequestItems.size(),
                        joiner,
                        reqBytes);
            }

            if (reqBytes > softLimitBytes && pendingRequestItems.size() > 1) {
                // the multi-item request exceeds the soft limit
                // try to remove the last item from the request and try sending again
                metrics().recordMultiItemRequestExceedsSoftLimit();
                logger.trace(
                        "{} Multi-item request exceeds soft limit; will attempt to remove last item and send again (items: {}, requestSize: {}, softLimitBytes: {})",
                        BlockNodeStreamingConnection.this,
                        pendingRequestItems.size(),
                        reqBytes,
                        softLimitBytes);
                // remove the last item from the pending item set and update state to reflect the removal of the item
                final BufferedItem item = pendingRequestItems.removeLast();
                --itemIndex;
                pendingRequestBytes -= (item.size() + requestItemPaddingBytes);
                if (item.isProof()) {
                    pendingRequestHasBlockProof = false;
                }
                if (item.isHeader()) {
                    pendingRequestHasBlockHeader = false;
                }
                return trySendPendingRequest();
            } else if (reqBytes > hardLimitBytes) {
                // the request exceeds the hard limit size... abandon all hope
                metrics().recordRequestExceedsHardLimit();
                logger.error(
                        "{} !!! FATAL: Request exceeds maximum size hard limit of {} bytes "
                                + "(block: {}, requestSize: {} bytes); Closing connection",
                        BlockNodeStreamingConnection.this,
                        hardLimitBytes,
                        block.blockNumber(),
                        reqBytes);
                close(CloseReason.INTERNAL_ERROR, true, EndStream.Code.ERROR);
                return false;
            }

            if (logger.isTraceEnabled()) {
                final StringJoiner joiner = new StringJoiner(",");
                pendingRequestItems.forEach(item -> joiner.add(Integer.toString(item.index())));
                logger.trace(
                        "{} Attempting to send request (block: {}, request: {}, items: {}=[{}], bytes: {})",
                        BlockNodeStreamingConnection.this,
                        block.blockNumber(),
                        requestCtr.get(),
                        pendingRequestItems.size(),
                        joiner,
                        reqBytes);
            }

            final long timeoutMillis = calculateRequestTimeout(reqBytes);

            try {
                final SendRequestResult result = sendRequest(new PublisherBlockItemsRequest(
                        req,
                        block.blockNumber(),
                        requestCtr.get(),
                        pendingRequestItems.size(),
                        pendingRequestHasBlockProof,
                        pendingRequestHasBlockHeader,
                        timeoutMillis,
                        blockAttemptNumber));
                if (SendRequestStatus.SUCCESS == result.status()) {
                    // record that we've sent the request
                    lastSendTimeMillis = System.currentTimeMillis();

                    streamingObs.onBlockItemsSend(
                            block.blockNumber(),
                            itemIndex - pendingRequestItems.size(),
                            itemIndex - 1,
                            result.context().startNanos(),
                            result.context().endNanos());

                    // clear the pending request data
                    resetForNextRequest();
                    return true;
                } else if (SendRequestStatus.TIMEOUT == result.status()) {
                    logger.warn(
                            "{} Timed out sending request; resetting to block start for retry (block: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber());
                    resetForBlockStart(true);
                    return false;
                } else {
                    logger.warn(
                            "{} Sending the request failed for a non-exceptional reason (block: {}, request: {}, itemCount: {}, bytes: {}, reason: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get(),
                            pendingRequestItems.size(),
                            reqBytes,
                            result.status());
                }
            } catch (final UncheckedIOException e) {
                final FailureType failureType = FailureType.findFailureType(e);
                if (failureType.isCommonFailure()) {
                    logger.warn(
                            "{} UncheckedIOException caught in connection worker thread (block: {}, request: {}, itemCount: {}, bytes: {}, error: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get(),
                            pendingRequestItems.size(),
                            reqBytes,
                            failureType);
                } else {
                    logger.warn(
                            "{} UncheckedIOException caught in connection worker thread (block: {}, request: {}, itemCount: {}, bytes: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get(),
                            pendingRequestItems.size(),
                            reqBytes,
                            e);
                }
                close(CloseReason.CONNECTION_ERROR, false, EndStream.Code.ERROR);
            } catch (final Exception e) {
                final FailureType failureType = FailureType.findFailureType(e);
                if (failureType.isCommonFailure()) {
                    logger.warn(
                            "{} Exception caught in connection worker thread (block: {}, request: {}, itemCount: {}, bytes: {}, error: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get(),
                            pendingRequestItems.size(),
                            reqBytes,
                            failureType);
                } else {
                    logger.warn(
                            "{} Exception caught in connection worker thread (block: {}, request: {}, itemCount: {}, bytes: {})",
                            BlockNodeStreamingConnection.this,
                            block.blockNumber(),
                            requestCtr.get(),
                            pendingRequestItems.size(),
                            reqBytes,
                            e);
                }
                close(CloseReason.CONNECTION_ERROR, true, EndStream.Code.ERROR);
            }

            return false;
        }

        /**
         * Resets internal counters in preparation for the next request for the current block.
         */
        private void resetForNextRequest() {
            pendingRequestBytes = requestBasePaddingBytes;
            pendingRequestItems.clear();
            requestCtr.incrementAndGet();
            pendingRequestHasBlockProof = false;
            pendingRequestHasBlockHeader = false;
        }

        /**
         * Resets internal counters in preparation for sending a new block. If this is called due to a retry, then the
         * internal counter for the number of attempts is incremented, otherwise it is reset as if we are moving to a
         * new, unsent block.
         *
         * @param isRetry true if the reset is due to a timeout/retry, else false
         */
        private void resetForBlockStart(final boolean isRetry) {
            itemIndex = 0;
            pendingRequestItems.clear();
            pendingRequestBytes = requestBasePaddingBytes;
            requestCtr.set(1);
            pendingRequestHasBlockHeader = false;
            pendingRequestHasBlockProof = false;

            if (isRetry) {
                ++blockAttemptNumber;
            } else {
                blockAttemptNumber = 1;
            }
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
            block = blockBufferService.getBlockState(latestActiveBlockNumber);

            if (block == null && latestActiveBlockNumber < blockBufferService.getEarliestAvailableBlockNumber()) {
                // Indicate that the block node should catch up from another trustworthy block node
                logger.warn(
                        "{} Wanted block ({}) is not obtainable; notifying block node it is too far behind and closing connection",
                        BlockNodeStreamingConnection.this,
                        latestActiveBlockNumber);
                close(CloseReason.BLOCK_NODE_BEHIND, true, EndStream.Code.TOO_FAR_BEHIND);
                return;
            }

            resetForBlockStart(false);

            if (block == null) {
                logger.trace(
                        "{} Wanted to switch from block {} to block {}, but it is not available",
                        BlockNodeStreamingConnection.this,
                        connectionStatistics().lastBlockSent(),
                        latestActiveBlockNumber);
            } else {
                logger.trace(
                        "{} Switched from block {} to block {}",
                        BlockNodeStreamingConnection.this,
                        connectionStatistics().lastBlockSent(),
                        latestActiveBlockNumber);
            }
        }
    }
}
