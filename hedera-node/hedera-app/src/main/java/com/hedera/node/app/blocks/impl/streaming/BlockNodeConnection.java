// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.PublishStreamResponse.EndOfStream;
import com.hedera.hapi.block.PublishStreamResponse.ResendBlock;
import com.hedera.hapi.block.PublishStreamResponse.SkipBlock;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Represents a single connection to a block node. Each connection is responsible for connecting to configured block nodes
 */
public class BlockNodeConnection implements StreamObserver<PublishStreamResponse> {
    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);
    private static final int MAX_END_OF_STREAM_RESTARTS = 3;
    private static final int MAX_END_OF_STREAM_EXP_RETRIES = 10;
    private final ScheduledExecutorService scheduler;

    private final BlockNodeConfig blockNodeConfig;
    private final GrpcServiceClient grpcServiceClient;
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    private final BlockStreamStateManager blockStreamStateManager;
    private BlockStreamMetrics blockStreamMetrics = null;
    private final String connectionDescriptor;

    // Locks and synchronization objects
    private final Object channelLock = new Object();
    private final Object workerLock = new Object();
    private final ReentrantLock connectionStateLock = new ReentrantLock();

    // Atomic state variables
    private final AtomicBoolean streamCompletionInProgress = new AtomicBoolean(false);
    private final AtomicLong currentBlockNumber = new AtomicLong(-1);
    private final AtomicInteger currentRequestIndex = new AtomicInteger(0);
    private final AtomicInteger endOfStreamImmediateRestarts = new AtomicInteger(0);

    // Notification objects
    private final Object newBlockAvailable = new Object();
    private final Object newRequestAvailable = new Object();

    // Volatile connection state
    private volatile StreamObserver<PublishStreamRequest> requestObserver;
    private volatile Thread requestWorker;
    private volatile ConnectionState connectionState;

    /**
     * Enum representing the current state of the connection.
     */
    public enum ConnectionState {
        /**
         * Connection is not yet initialized.
         */
        UNINITIALIZED,
        /**
         * Connection is active.
         */
        ACTIVE,
        /**
         * Connection is currently retrying.
         */
        RETRYING,
        /**
         * Connection is closed.
         */
        CLOSED
    }

    protected BlockNodeConnection() {
        // Default constructor for NoOpConnection
        this.blockNodeConfig = null;
        this.grpcServiceClient = null;
        this.blockNodeConnectionManager = null;
        this.blockStreamStateManager = null;
        this.scheduler = null;
        this.connectionDescriptor = null;
    }

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param nodeConfig the configuration for the block node
     * @param blockNodeConnectionManager the connection manager for block node connections
     * @param blockStreamStateManager the block stream state manager for block node connections
     * @param grpcServiceClient the gRPC client to establish the bidirectional streaming to block node connections
     * @param scheduler the scheduler for the connection
     * @param blockStreamMetrics the block stream metrics for block node connections
     */
    public BlockNodeConnection(
            @NonNull final BlockNodeConfig nodeConfig,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final BlockStreamStateManager blockStreamStateManager,
            @NonNull final GrpcServiceClient grpcServiceClient,
            @NonNull final ScheduledExecutorService scheduler,
            @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.blockNodeConfig = requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        this.grpcServiceClient = requireNonNull(grpcServiceClient, "grpcServiceClient must not be null");
        this.scheduler = requireNonNull(scheduler, "scheduler must not be null");
        this.blockStreamMetrics = requireNonNull(blockStreamMetrics, "blockStreamMetrics must not be null");
        this.connectionDescriptor = generateConnectionDescriptor(nodeConfig);
        this.connectionState = ConnectionState.UNINITIALIZED;
    }

    /**
     * Establish the bidirectional streaming to block nodes.
     */
    public void establishStream() {
        synchronized (connectionStateLock) {
            synchronized (channelLock) {
                requestObserver = grpcServiceClient.bidi(blockNodeConnectionManager.getGrpcEndPoint(), this);

                // Check if this is the highest priority connection
                if (getConnectionState() != ConnectionState.RETRYING) {
                    logger.debug("Stream to block node {} is ACTIVE", connectionDescriptor);
                    updateConnectionState(ConnectionState.ACTIVE);
                    startRequestWorker();
                } else if (getConnectionState() == ConnectionState.RETRYING
                        && blockNodeConnectionManager.getNextPriorityBlockNode() == null) {
                    logger.debug(
                            "No other priority connection available, making current stream to block node {} ACTIVE",
                            connectionDescriptor);
                    updateConnectionState(ConnectionState.ACTIVE);
                    startRequestWorker();
                }
            }
        }
    }

    /**
     * @param newState the new state to transition to
     */
    public void updateConnectionState(@NonNull ConnectionState newState) {
        synchronized (connectionStateLock) {
            connectionState = newState;
        }
    }

    /**
     * @return the current state of the connection
     */
    @VisibleForTesting
    ConnectionState getConnectionState() {
        return connectionState;
    }

    private void startRequestWorker() {
        synchronized (workerLock) {
            if (requestWorker != null && requestWorker.isAlive()) {
                stopWorkerThread();
            }
            if (isActive()) {
                requestWorker = Thread.ofPlatform()
                        .name("BlockNodeConnection-RequestWorker-" + connectionDescriptor)
                        .start(this::requestWorkerLoop);
                logger.debug("Started request worker thread for block node {}", connectionDescriptor);
            }
        }
    }

    private void requestWorkerLoop() {
        while (isActive()) {
            try {
                final var currentBlock = getCurrentBlockNumber();
                // Get the current block state
                final BlockState blockState = blockStreamStateManager.getBlockState(currentBlock);

                // If block state is null, check if we're behind
                if (blockState == null && currentBlock != -1) {
                    long lowestAvailableBlock = blockStreamStateManager.getBlockNumber();
                    if (lowestAvailableBlock > currentBlock) {
                        logger.debug(
                                "[{}] Block {} state not found and lowest available block is {}, ending stream for node {}",
                                Thread.currentThread().getName(),
                                currentBlock,
                                lowestAvailableBlock,
                                connectionDescriptor);
                        handleStreamFailure();
                        return;
                    }
                }

                // Otherwise wait for new block if we're at -1 or the current block isn't available yet
                if (currentBlock == -1 || blockState == null) {
                    logger.debug(
                            "[{}] Waiting for new block to be available for node {}",
                            Thread.currentThread().getName(),
                            connectionDescriptor);
                    waitForNewBlock();
                    continue;
                }

                logBlockProcessingInfo(blockState);

                // If there are no requests yet, wait for some to be added
                if (blockState.requests().isEmpty() && !blockState.isComplete()) {
                    waitForNewRequests();
                    continue;
                }

                // If we've processed all available requests but the block isn't complete,
                // wait for more requests to be added
                if (needToWaitForMoreRequests(blockState)) {
                    waitForNewRequests();
                    continue;
                }

                // Process any available requests
                processAvailableRequests(blockState);

                // If the block is complete and we've sent all requests, move to the next block
                if (blockState.isComplete()
                        && getCurrentRequestIndex() >= blockState.requests().size()) {
                    // Check if there is a higher priority ready connection
                    if (!blockNodeConnectionManager.isHigherPriorityReady(this)) {
                        moveToNextBlock();
                    }
                }
            } catch (InterruptedException e) {
                logger.error(
                        "[{}] Request worker thread interrupted for node {}",
                        Thread.currentThread().getName(),
                        connectionDescriptor);
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.error(
                        "[{}] Error in request worker thread for node {}",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        e);
                handleStreamFailure();
            }
        }
        logger.debug(
                "[{}] Request worker thread exiting for node {}",
                Thread.currentThread().getName(),
                connectionDescriptor);
    }

    private void waitForNewBlock() throws InterruptedException {
        synchronized (newBlockAvailable) {
            newBlockAvailable.wait();
        }
    }

    private void waitForNewRequests() throws InterruptedException {
        final var currentBlock = getCurrentBlockNumber();
        logger.debug(
                "{}} Waiting for new requests to be available for block {} on node {}, "
                        + "currentRequestIndex: {}, requestsSize: {}",
                Thread.currentThread().getName(),
                currentBlock,
                connectionDescriptor,
                getCurrentRequestIndex(),
                blockStreamStateManager.getBlockState(currentBlock) != null
                        ? blockStreamStateManager
                                .getBlockState(currentBlock)
                                .requests()
                                .size()
                        : 0);
        synchronized (newRequestAvailable) {
            newRequestAvailable.wait();
        }
    }

    private void logBlockProcessingInfo(BlockState blockState) {
        logger.debug(
                "[{}] Processing block {} for node {}, isComplete: {}, requests: {}",
                Thread.currentThread().getName(),
                getCurrentBlockNumber(),
                connectionDescriptor,
                blockState.isComplete(),
                blockState.requests().size());
    }

    private boolean needToWaitForMoreRequests(@NonNull BlockState blockState) {
        return getCurrentRequestIndex() >= blockState.requests().size() && !blockState.isComplete();
    }

    private void processAvailableRequests(@NonNull BlockState blockState) {
        synchronized (connectionStateLock) {
            List<PublishStreamRequest> requests = blockState.requests();
            while (getCurrentRequestIndex() < requests.size()) {
                if (!isActive()) {
                    return;
                }
                final PublishStreamRequest request = requests.get(getCurrentRequestIndex());
                logger.debug(
                        "[{}] Sending request for block {} request index {} to node {}, items: {}",
                        Thread.currentThread().getName(),
                        getCurrentBlockNumber(),
                        getCurrentRequestIndex(),
                        connectionDescriptor,
                        request.blockItems().blockItems().size());
                sendRequest(request);
                currentRequestIndex.incrementAndGet();
            }
        }
    }

    private void moveToNextBlock() {
        logger.debug(
                "[{}] Completed sending all requests for block {} to node {}",
                Thread.currentThread().getName(),
                getCurrentBlockNumber(),
                connectionDescriptor);
        currentBlockNumber.incrementAndGet();
        currentRequestIndex.set(0);
    }

    private void handleStreamFailure() {
        close();
        blockNodeConnectionManager.handleConnectionError(this);
    }

    private void handleEndOfStreamError() {
        scheduler.schedule(
                () -> {
                    logger.debug(
                            "[{}] Attempting retry after internal error for node {} at block {}",
                            Thread.currentThread().getName(),
                            connectionDescriptor,
                            getCurrentBlockNumber());
                    blockNodeConnectionManager.handleConnectionError(this);
                },
                5,
                TimeUnit.SECONDS);
    }

    private void handleAcknowledgement(@NonNull Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            final var blockAck = acknowledgement.blockAck();
            final var acknowledgedBlockNumber = blockAck.blockNumber();
            final var blockAlreadyExists = blockAck.blockAlreadyExists();
            final var currentBlock = getCurrentBlockNumber();

            // Update the last verified block by the current connection
            blockNodeConnectionManager.updateLastVerifiedBlock(blockNodeConfig, acknowledgedBlockNumber);
            // Remove all block states up to and including this block number
            blockStreamStateManager.removeBlockStatesUpTo(acknowledgedBlockNumber);

            if (blockAlreadyExists) {
                logger.warn("Block {} already exists on block node {}", acknowledgedBlockNumber, connectionDescriptor);
            } else {
                logger.debug(
                        "Block {} acknowledged and successfully processed by block node {}",
                        acknowledgedBlockNumber,
                        connectionDescriptor);
            }

            if (currentBlock > acknowledgedBlockNumber) {
                logger.debug(
                        "Current block number {} is higher than the acknowledged block number {}",
                        currentBlock,
                        acknowledgedBlockNumber);
            } else if (currentBlock < acknowledgedBlockNumber) {
                logger.debug(
                        "Consensus node is behind and current block number {} is before the acknowledged block number {}",
                        currentBlock,
                        acknowledgedBlockNumber);
                jumpToBlock(acknowledgedBlockNumber + 1);
            }
        } else {
            logger.warn("Unknown acknowledgement received: {}", acknowledgement);
        }
    }

    private void handleEndOfStream(@NonNull EndOfStream endOfStream) {
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

                handleEndOfStreamError();
            }
            case STREAM_ITEMS_SUCCESS,
                    STREAM_ITEMS_TIMEOUT,
                    STREAM_ITEMS_OUT_OF_ORDER,
                    STREAM_ITEMS_BAD_STATE_PROOF -> {
                // We should restart the stream at the block immediately
                // following the block where the node fell behind.
                long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                logger.warn(
                        "[{}] Block node {} reported status indicating immediate restart should be attempted. Will restart stream at block {}.",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        restartBlockNumber);

                if (endOfStreamImmediateRestarts.incrementAndGet() <= MAX_END_OF_STREAM_RESTARTS) {
                    restartStreamAtBlock(restartBlockNumber);
                } else {
                    handleEndOfStreamError();
                }
            }
            case STREAM_ITEMS_BEHIND -> {
                // The block node is behind us, check if we have the last verified block still available in order to
                // restart the stream from there
                long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
                if (blockStreamStateManager.getBlockState(restartBlockNumber) != null) {
                    logger.warn(
                            "[{}] Block node {} reported it is behind. Will restart stream at block {}.",
                            Thread.currentThread().getName(),
                            connectionDescriptor,
                            restartBlockNumber);
                    if (endOfStreamImmediateRestarts.incrementAndGet() <= MAX_END_OF_STREAM_RESTARTS) {
                        restartStreamAtBlock(restartBlockNumber);
                    } else {
                        handleEndOfStreamError();
                    }
                } else {
                    // If we don't have the block state, we schedule retry for this connection and establish new one
                    // with different block node
                    logger.warn(
                            "[{}] Block node {} is behind and block state is not available. Closing connection and retrying.",
                            Thread.currentThread().getName(),
                            connectionDescriptor);

                    blockNodeConnectionManager.handleConnectionError(this);
                }
            }
            case STREAM_ITEMS_UNKNOWN -> {
                // This should never happen, but if it does, we should close the connection
                logger.error(
                        "[{}] Block node {} reported an unknown error at block {}. Closing connection.",
                        Thread.currentThread().getName(),
                        connectionDescriptor,
                        blockNumber);
            }
        }
    }

    private void handleSkipBlock(@NonNull SkipBlock skipBlock) {
        final var skipBlockNumber = skipBlock.blockNumber();

        if (skipBlockNumber == getCurrentBlockNumber()) {
            logger.debug("Received SkipBlock from block node {} for block {}", connectionDescriptor, skipBlockNumber);
            jumpToBlock(skipBlockNumber + 1L);
        }
    }

    private void handleResendBlock(@NonNull ResendBlock resendBlock) {
        final var resendBlockNumber = resendBlock.blockNumber();

        logger.debug(
                "[{}] Received ResendBlock from block node {} for block {}",
                Thread.currentThread().getName(),
                connectionDescriptor,
                resendBlockNumber);

        if (blockNodeConnectionManager.isBlockAlreadyAcknowledged(resendBlockNumber)) {
            logger.debug(
                    "[{}] Block {} already acknowledged, skipping resend for block node {}",
                    Thread.currentThread().getName(),
                    resendBlockNumber,
                    connectionDescriptor);
            return;
        }

        final var lastVerifiedBlockNumber = blockNodeConnectionManager.getLastVerifiedBlock(blockNodeConfig);
        // Check whether the resend block number is the next block after the last verified one
        if (resendBlockNumber == lastVerifiedBlockNumber + 1L) {
            close();

            logger.debug(
                    "[{}] Restarting stream at the next block {} after the last verified one for block node {}",
                    Thread.currentThread().getName(),
                    resendBlockNumber,
                    connectionDescriptor);
            restartStreamAtBlock(resendBlockNumber);
        } else {
            logger.warn(
                    "[{}] Received ResendBlock for block {} but last verified block is {}",
                    Thread.currentThread().getName(),
                    resendBlockNumber,
                    lastVerifiedBlockNumber);
        }
    }

    private String generateConnectionDescriptor(BlockNodeConfig nodeConfig) {
        return nodeConfig.address() + ":" + nodeConfig.port();
    }

    /**
     * If connection is active sends a request to the block node, otherwise does nothing.
     *
     * @param request the request to send
     */
    public void sendRequest(@NonNull final PublishStreamRequest request) {
        requireNonNull(request);
        synchronized (connectionStateLock) {
            synchronized (channelLock) {
                if (isActive() && requestObserver != null) {
                    requestObserver.onNext(request);
                }
            }
        }
    }

    /**
     * Idempotent operation that closes this connection (if active)
     */
    public void close() {
        synchronized (connectionStateLock) {
            if (isActive()) {
                updateConnectionState(ConnectionState.CLOSED);
                closeObserver();
                setCurrentBlockNumber(-1);
            }
        }
        logger.debug("Closed connection to block node {}", connectionDescriptor);
    }

    private void closeObserver() {
        synchronized (channelLock) {
            if (requestObserver != null) {
                try {
                    requestObserver.onCompleted();
                } catch (Exception e) {
                    logger.warn("Error while completing request observer", e);
                }
                requestObserver = null;
            }
        }
        stopWorkerThread();
    }

    /**
     * Returns whether the connection is active.
     *
     * @return true if the connection is active, false otherwise
     */
    public boolean isActive() {
        return getConnectionState() == ConnectionState.ACTIVE;
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
     * Gets the current block number being processed.
     *
     * @return the current block number
     */
    public long getCurrentBlockNumber() {
        return currentBlockNumber.get();
    }

    /**
     * Gets the current request index being processed.
     *
     * @return the current request index
     */
    public int getCurrentRequestIndex() {
        return currentRequestIndex.get();
    }

    public ReentrantLock getConnectionStateLock() {
        return connectionStateLock;
    }

    public void notifyNewRequestAvailable() {
        final var currentBlock = getCurrentBlockNumber();
        synchronized (newRequestAvailable) {
            BlockState blockState = blockStreamStateManager.getBlockState(currentBlock);
            if (blockState != null) {
                logger.debug(
                        "Notifying of new request available for node {} - block: {}, requests: {}, isComplete: {}",
                        connectionDescriptor,
                        currentBlock,
                        blockState.requests().size(),
                        blockState.isComplete());
            } else {
                logger.debug(
                        "Notifying of new request available for node {} - block: {} (state not found)",
                        connectionDescriptor,
                        currentBlock);
            }
            newRequestAvailable.notify();
        }
    }

    public void notifyNewBlockAvailable() {
        synchronized (newBlockAvailable) {
            newBlockAvailable.notify();
        }
    }

    public void setCurrentBlockNumber(long blockNumber) {
        currentBlockNumber.set(blockNumber);
        currentRequestIndex.set(0); // Reset the request index when setting a new block
        logger.debug(
                "[{}] Set current block number to {} for node {}, reset request index to 0",
                Thread.currentThread().getName(),
                blockNumber,
                connectionDescriptor);
    }

    /**
     * Restarts the worker thread at a specific block number without ending the stream.
     * This method will interrupt the current worker thread if it exists,
     * set the new block number and request index, and start a new worker thread.
     * The gRPC stream with the block node is maintained.
     *
     * @param blockNumber the block number to jump to
     */
    public void jumpToBlock(long blockNumber) {
        logger.debug(
                "Setting current block number to {} for node {} without ending stream",
                blockNumber,
                connectionDescriptor);

        stopWorkerThread();
        setCurrentBlockNumber(blockNumber);
        startRequestWorker();
        notifyNewBlockAvailable();

        logger.debug("Worker thread restarted and jumped to block {} for node {}", blockNumber, connectionDescriptor);
    }

    /**
     * Restarts a new stream at a specific block number.
     * This method will establish a new stream and start processing from the specified block number.
     *
     * @param blockNumber the block number to restart at
     */
    public void restartStreamAtBlock(long blockNumber) {
        logger.debug("Restarting stream at block {} for node {}", blockNumber, connectionDescriptor);

        synchronized (connectionStateLock) {
            synchronized (channelLock) {
                setCurrentBlockNumber(blockNumber);
                establishStream();
            }
        }

        logger.debug("Stream restarted at block {} for node {}", blockNumber, connectionDescriptor);
    }

    /**
     * Stops the current worker thread if it exists and waits for it to terminate.
     */
    private void stopWorkerThread() {
        synchronized (workerLock) {
            if (requestWorker != null) {
                requestWorker.interrupt();
                try {
                    requestWorker.join(5000); // Wait up to 5 seconds for thread to stop
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while waiting for request worker to stop");
                }
                requestWorker = null;
            }
        }
    }

    @Override
    public void onNext(@NonNull PublishStreamResponse response) {
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

    @Override
    public void onError(Throwable error) {
        logger.error(
                "[{}] Error on stream from block node {}",
                Thread.currentThread().getName(),
                connectionDescriptor,
                error);
        handleStreamFailure();
    }

    @Override
    public void onCompleted() {
        if (streamCompletionInProgress.compareAndSet(false, true)) {
            try {
                logger.debug(
                        "[{}] Stream completed for block node {}",
                        Thread.currentThread().getName(),
                        connectionDescriptor);
                handleStreamFailure();
            } finally {
                streamCompletionInProgress.set(false);
            }
        }
    }
}
