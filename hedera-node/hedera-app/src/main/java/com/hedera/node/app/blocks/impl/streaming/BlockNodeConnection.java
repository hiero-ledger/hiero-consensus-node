// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.PublishStreamResponse.EndOfStream;
import com.hedera.hapi.block.PublishStreamResponse.ResendBlock;
import com.hedera.hapi.block.PublishStreamResponse.SkipBlock;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
public class BlockNodeConnection implements StreamObserver<PublishStreamResponse> {
    /**
     * A longer retry delay for when the connection encounters an error.
     */
    public static final Duration LONGER_RETRY_DELAY = Duration.ofSeconds(30);

    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);

    private final BlockNodeConfig blockNodeConfig;
    private final GrpcServiceClient grpcServiceClient;
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    private final BlockStreamStateManager blockStreamStateManager;
    private BlockStreamMetrics blockStreamMetrics = null;
    private final String connectionDescriptor;

    // The EndOfStream rate limit allowed in a time frame
    private final Integer maxEndOfStreamsAllowed;
    private final Duration endOfStreamTimeFrame;
    private final Duration endOfStreamScheduleDelay;
    private final Queue<Instant> endOfStreamTimestamps = new ConcurrentLinkedQueue<>();

    // Atomic state variables
    private final AtomicBoolean streamCompletionInProgress = new AtomicBoolean(false);

    // Volatile connection state
    private volatile StreamObserver<PublishStreamRequest> requestObserver;
    private volatile ConnectionState connectionState;

    /**
     * Represents the possible states of a Block Node connection:
     * <ul>
     *   <li>UNINITIALIZED - Initial state before stream establishment</li>
     *   <li>PENDING - Stream established but not selected as active</li>
     *   <li>ACTIVE - Connection is actively streaming blocks</li>
     * </ul>
     */
    public enum ConnectionState {
        /**
         * bidi RequestObserver needs to be created.
         */
        UNINITIALIZED,
        /**
         * bidi RequestObserver is established but this connection has not been chosen as the active one (priority based).
         */
        PENDING,
        /**
         * Connection is active. Block Stream Worker Thread is sending PublishStreamRequest's to the block node through async bidi stream.
         */
        ACTIVE
    }

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param configProvider the configuration to use
     * @param nodeConfig the configuration for the block node
     * @param blockNodeConnectionManager the connection manager coordinating block node connections
     * @param blockStreamStateManager the block stream state manager for block node connections
     * @param grpcServiceClient the gRPC client to establish the bidirectional streaming to block node connections
     * @param blockStreamMetrics the block stream metrics for block node connections
     */
    public BlockNodeConnection(
            @NonNull final ConfigProvider configProvider,
            @NonNull final BlockNodeConfig nodeConfig,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final BlockStreamStateManager blockStreamStateManager,
            @NonNull final GrpcServiceClient grpcServiceClient,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        requireNonNull(configProvider, "configProvider must not be null");
        this.blockNodeConfig = requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        this.grpcServiceClient = requireNonNull(grpcServiceClient, "grpcServiceClient must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.connectionDescriptor = generateConnectionDescriptor(nodeConfig);
        this.connectionState = ConnectionState.UNINITIALIZED;

        final var blockNodeConnectionConfig =
                configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);

        this.maxEndOfStreamsAllowed = blockNodeConnectionConfig.maxEndOfStreamsAllowed();
        this.endOfStreamTimeFrame = blockNodeConnectionConfig.endOfStreamTimeFrame();
        this.endOfStreamScheduleDelay = blockNodeConnectionConfig.endOfStreamScheduleDelay();
    }

    /**
     * Creates a new bidi request observer for this block node connection.
     */
    public void createRequestObserver() {
        requestObserver = grpcServiceClient.bidi(blockNodeConnectionManager.getGrpcEndPoint(), this);
    }

    /**
     * Updates the connection's state.
     * @param newState the new state to transition to
     */
    public void updateConnectionState(@NonNull final ConnectionState newState) {
        connectionState = newState;
    }

    /**
     * Handles the failure of the stream by closing the connection and notifying the connection manager.
     */
    public void handleStreamFailure() {
        close();
        blockNodeConnectionManager.handleConnectionError(this, LONGER_RETRY_DELAY);
    }

    /**
     * Handles the {@link Acknowledgement} response received from the block node.
     *
     * @param acknowledgement the acknowledgement received from the block node
     */
    private void handleAcknowledgement(@NonNull final Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            final var blockAck = acknowledgement.blockAck();
            final var acknowledgedBlockNumber = blockAck.blockNumber();
            final var blockAlreadyExists = blockAck.blockAlreadyExists();
            synchronized (blockNodeConnectionManager.getStreamingBlockNumber()) {
                final long currentBlockStreaming =
                        blockNodeConnectionManager.getStreamingBlockNumber().get();
                final var currentBlockProducing = blockStreamStateManager.getBlockNumber();

                // Update the last verified block by the current connection
                blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, acknowledgedBlockNumber);

                // Increment the ACK counter metric
                blockStreamMetrics.incrementBlockAckReceivedCount();

                if (currentBlockStreaming == -1) {
                    return;
                }

                if (blockAlreadyExists) {
                    logger.debug(
                            "[{}] Block {} already exists on block node {}",
                            Thread.currentThread().getName(),
                            acknowledgedBlockNumber,
                            connectionDescriptor);
                } else {
                    logger.debug(
                            "[{}] Block {} acknowledgement received from block node {}",
                            Thread.currentThread().getName(),
                            acknowledgedBlockNumber,
                            connectionDescriptor);
                }

                if (currentBlockStreaming < acknowledgedBlockNumber
                        && currentBlockProducing >= acknowledgedBlockNumber) {
                    // We can jump to streaming the acknowledged block number
                    logger.debug(
                            "[{}] Currently streaming Block {} to Block Node {} and acknowledged Block {} - moving streaming ahead to Block {}",
                            Thread.currentThread().getName(),
                            currentBlockStreaming,
                            connectionDescriptor,
                            acknowledgedBlockNumber,
                            acknowledgedBlockNumber + 1L);
                    jumpToBlock(acknowledgedBlockNumber + 1);
                } else if (currentBlockStreaming == acknowledgedBlockNumber
                        && currentBlockProducing == acknowledgedBlockNumber) {
                    // We are already streaming the acknowledged block number
                    logger.debug(
                            "[{}] Currently streaming Block {} to Block Node {} and acknowledged Block {} - moving streaming ahead to Block {}",
                            Thread.currentThread().getName(),
                            currentBlockStreaming,
                            connectionDescriptor,
                            acknowledgedBlockNumber,
                            acknowledgedBlockNumber + 1L);
                    jumpToBlock(acknowledgedBlockNumber + 1);
                } else if (currentBlockStreaming < acknowledgedBlockNumber) {
                    logger.debug(
                            "[{}] Currently producing Block {} to Block Node {} and acknowledged Block {} - moving streaming ahead to Block {}",
                            Thread.currentThread().getName(),
                            currentBlockProducing,
                            connectionDescriptor,
                            acknowledgedBlockNumber,
                            acknowledgedBlockNumber + 1L);
                    jumpToBlock(acknowledgedBlockNumber + 1);
                }
            }
        } else {
            logger.warn("Unknown acknowledgement received: {}", acknowledgement);
        }
    }

    /**
     * Handles the {@link EndOfStream} response received from the block node.
     * In most cases it indicates that the block node is unable to continue processing.
     * @param endOfStream the EndOfStream response received from the block node
     */
    private void handleEndOfStream(@NonNull final EndOfStream endOfStream) {
        final var blockNumber = endOfStream.blockNumber();
        final var responseCode = endOfStream.status();

        logger.debug(
                "[{}] Received EndOfStream from block node {} at block {} with PublishStreamResponseCode {}",
                Thread.currentThread().getName(),
                connectionDescriptor,
                blockNumber,
                responseCode);

        // Always end the stream when we receive an end of stream message
        close();

        // Check if we've exceeded the EndOfStream rate limit
        if (hasExceededEndOfStreamLimit()) {
            logger.warn(
                    "[{}] Block node {} exceeded EndOfStream rate limit ({} in {} seconds). Delaying reconnection by {} seconds.",
                    Thread.currentThread().getName(),
                    connectionDescriptor,
                    maxEndOfStreamsAllowed,
                    endOfStreamTimeFrame.toSeconds(),
                    endOfStreamScheduleDelay.toSeconds());

            // Schedule delayed retry through connection manager
            blockNodeConnectionManager.handleConnectionError(this, endOfStreamScheduleDelay);
            return;
        }

        switch (responseCode) {
            case STREAM_ITEMS_INTERNAL_ERROR, STREAM_ITEMS_PERSISTENCE_FAILED -> {
                // The block node had an end of stream error and cannot continue processing.
                // We should wait for a short period before attempting to retry
                // to avoid overwhelming the node if it's having issues
                logger.warn(
                        "[{}] Block node {} reported an error at block {}. Will attempt to reestablish the stream later.",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        blockNumber);
                blockNodeConnectionManager.handleConnectionError(this, LONGER_RETRY_DELAY);
            }
            case STREAM_ITEMS_TIMEOUT, STREAM_ITEMS_OUT_OF_ORDER, STREAM_ITEMS_BAD_STATE_PROOF -> {
                // We should restart the stream at the block immediately
                // following the block where the node fell behind.
                final long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                logger.warn(
                        "[{}] Block node {} reported status indicating immediate restart should be attempted. Will restart stream at block {}.",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        restartBlockNumber);

                restartStreamAtBlock(restartBlockNumber);
            }
            case STREAM_ITEMS_SUCCESS -> {
                // The block node orderly ended the stream. In this case, no errors occurred.
                // We should wait for a longer period before attempting to retry.
                logger.warn(
                        "[{}] Block node {} orderly ended the stream at block {}",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        blockNumber);
                blockNodeConnectionManager.handleConnectionError(this, LONGER_RETRY_DELAY);
            }
            case STREAM_ITEMS_BEHIND -> {
                // The block node is behind us, check if we have the last verified block still available in order to
                // restart the stream from there
                final long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                if (blockStreamStateManager.getBlockState(restartBlockNumber) != null) {
                    logger.warn(
                            "[{}] Block node {} reported it is behind. Will restart stream at block {}.",
                            Thread.currentThread().getName(),
                            connectionDescriptor,
                            restartBlockNumber);

                    restartStreamAtBlock(restartBlockNumber);
                } else {
                    // If we don't have the block state, we schedule retry for this connection and establish new one
                    // with different block node
                    logger.warn(
                            "[{}] Block node {} is behind and block state is not available. Closing connection and retrying.",
                            Thread.currentThread().getName(),
                            connectionDescriptor);

                    blockNodeConnectionManager.handleConnectionError(this, LONGER_RETRY_DELAY);
                }
            }
            case STREAM_ITEMS_UNKNOWN -> {
                // This should never happen, but if it does, we should close the connection
                logger.error(
                        "[{}] Block node {} reported an unknown error at block {}. Closing connection.",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        blockNumber);
                blockNodeConnectionManager.handleConnectionError(this, LONGER_RETRY_DELAY);
            }
        }
    }

    /**
     * Handles the {@link SkipBlock} response received from the block node.
     * @param skipBlock the SkipBlock response received from the block node
     */
    private void handleSkipBlock(@NonNull final SkipBlock skipBlock) {
        final var skipBlockNumber = skipBlock.blockNumber();
        final long streamingBlockNumber =
                blockNodeConnectionManager.getStreamingBlockNumber().get();

        // Only jump if the skip is for the block we are currently processing
        if (skipBlockNumber == streamingBlockNumber) {
            final var nextBlock = skipBlockNumber + 1L;
            logger.debug("Skipping ahead to Block {} because of SkipBlock from {}", nextBlock, connectionDescriptor);
            jumpToBlock(nextBlock); // Now uses signaling instead of thread interruption
        } else {
            logger.warn(
                    "Received SkipBlock for {} but currently processing block {}, ignoring. {}",
                    skipBlockNumber,
                    streamingBlockNumber,
                    connectionDescriptor);
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
        final var resendBlockNumber = resendBlock.blockNumber();
        logger.debug(
                "[{}] Received ResendBlock from block node {} for block {}",
                Thread.currentThread().getName(),
                connectionDescriptor,
                resendBlockNumber);

        if (blockStreamStateManager.getBlockState(resendBlockNumber) != null) {
            jumpToBlock(resendBlockNumber);
        } else {
            // If we don't have the block state, we schedule retry for this connection and establish new one
            // with different block node
            logger.warn(
                    "[{}] Block node {} requested a ResendBlock of {} and block state is not available. Closing connection and will retry later.",
                    Thread.currentThread().getName(),
                    connectionDescriptor,
                    resendBlockNumber);
            close();
            blockNodeConnectionManager.handleConnectionError(this, LONGER_RETRY_DELAY);
        }
    }

    /**
     * Checks if the EndOfStream rate limit has been exceeded.
     * This method maintains a queue of timestamps for the last EndOfStream responses
     * and checks if the number of responses exceeds the configurable limit
     * within the configurable time frame.
     *
     * @return true if the rate limit has been exceeded, false otherwise
     */
    private boolean hasExceededEndOfStreamLimit() {
        final var now = Instant.now();
        final var cutoff = now.minus(endOfStreamTimeFrame);

        // Remove expired timestamps
        while (!endOfStreamTimestamps.isEmpty() && endOfStreamTimestamps.peek().isBefore(cutoff)) {
            endOfStreamTimestamps.poll();
        }

        // Add current timestamp
        endOfStreamTimestamps.offer(now);

        // Check if we've exceeded the limit
        return endOfStreamTimestamps.size() > maxEndOfStreamsAllowed;
    }

    private String generateConnectionDescriptor(final BlockNodeConfig nodeConfig) {
        return nodeConfig.address() + ":" + nodeConfig.port();
    }

    /**
     * If connection is active sends a stream request to the block node, otherwise does nothing.
     *
     * @param request the request to send
     */
    public void sendRequest(@NonNull final PublishStreamRequest request) {
        requireNonNull(request);
        if (isActive() && requestObserver != null) {
            requestObserver.onNext(request);
        }
    }

    /**
     * Idempotent operation that closes this connection (if active)
     * and releases associated resources.
     */
    public void close() {
        updateConnectionState(ConnectionState.UNINITIALIZED);
        logger.debug(
                "[{}] BlockNodeConnection {} ConnectionState: {}",
                Thread.currentThread().getName(),
                connectionDescriptor,
                connectionState);
        closeObserver();
        jumpToBlock(-1L);

        logger.debug(
                "[{}] Closed connection to block node {}",
                Thread.currentThread().getName(),
                connectionDescriptor);
    }

    private void closeObserver() {
        if (requestObserver != null) {
            try {
                logger.debug(
                        "[{}] Closing request observer for block node - requestObserver.onCompleted() {}",
                        Thread.currentThread().getName(),
                        connectionDescriptor);
                streamCompletionInProgress.set(true);
                requestObserver.onCompleted();
            } catch (Exception e) {
                logger.warn("Error while completing request observer", e);
            }
            requestObserver = null;
        }
    }

    /**
     * Returns whether the connection is active.
     *
     * @return true if the connection is active, false otherwise
     */
    public boolean isActive() {
        return connectionState == ConnectionState.ACTIVE;
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
     * Restarts a new stream at a specified block number.
     * This method will establish a new stream and start processing from the specified block number.
     *
     * @param blockNumber the block number to restart at
     */
    public void restartStreamAtBlock(final long blockNumber) {
        logger.debug("Restarting stream at block {} for node {}", blockNumber, connectionDescriptor);

        blockNodeConnectionManager.scheduleRetry(this, BlockNodeConnectionManager.INITIAL_RETRY_DELAY, blockNumber);

        logger.debug("Stream restarted at block {} for node {}", blockNumber, connectionDescriptor);
    }

    /**
     * Restarts the worker thread at a specific block number without ending the stream.
     * This method will interrupt the current worker thread if it exists,
     * set the new block number and request index, and start a new worker thread.
     * The gRPC stream with the block node is maintained.
     *
     * @param blockNumber the block number to jump to
     */
    public void jumpToBlock(final long blockNumber) {
        logger.debug(
                "[{}] Jumping to block {} for node {}",
                Thread.currentThread().getName(),
                blockNumber,
                connectionDescriptor);
        // Set the target block for the worker loop to pick up
        blockNodeConnectionManager.getJumpTargetBlock().set(blockNumber);
    }

    /**
     * Processes responses received from the block node through the bidirectional gRPC stream.
     * Handles {@link Acknowledgement}s, {@link EndOfStream} response signals, {@link SkipBlock} and {@link ResendBlock}.
     *
     * @param response the response received from block node
     */
    @Override
    public void onNext(final PublishStreamResponse response) {
        if (response.hasAcknowledgement()) {
            handleAcknowledgement(response.acknowledgement());
        } else if (response.hasEndStream()) {
            blockStreamMetrics.incrementEndOfStreamCount(response.endStream().status()); // Remove nodeId
            handleEndOfStream(response.endStream());
        } else if (response.hasSkipBlock()) {
            blockStreamMetrics.incrementSkipBlockCount();
            logger.debug(
                    "Received SkipBlock from block node {}  Block #{}",
                    connectionDescriptor,
                    response.skipBlock().blockNumber());
            handleSkipBlock(response.skipBlock());
        } else if (response.hasResendBlock()) {
            blockStreamMetrics.incrementResendBlockCount();
            handleResendBlock(response.resendBlock());
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
        logger.error(
                "[{}] Error on stream from block node {}",
                Thread.currentThread().getName(),
                connectionDescriptor,
                error);
        blockStreamMetrics.incrementOnErrorCount();
        handleStreamFailure();
    }

    /**
     * Handles normal stream completion or termination.
     * Triggers reconnection if completion was not initiated by this side.
     */
    @Override
    public void onCompleted() {
        if (!streamCompletionInProgress.get()) {
            logger.debug(
                    "[{}] Stream completed for block node {}",
                    Thread.currentThread().getName(),
                    connectionDescriptor);
            handleStreamFailure();
        } else {
            logger.debug(
                    "[{}] Stream completed for block node {} (stream completion in progress)",
                    Thread.currentThread().getName(),
                    connectionDescriptor);
            streamCompletionInProgress.set(false);
        }
    }

    /**
     * Returns the connection state for this connection.
     *
     * @return the connection state
     */
    public ConnectionState getConnectionState() {
        return connectionState;
    }
}
