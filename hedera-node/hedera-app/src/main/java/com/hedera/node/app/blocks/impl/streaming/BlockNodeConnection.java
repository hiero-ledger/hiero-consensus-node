// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.util.List;
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

    private final BlockNodeConfig node;
    private final GrpcServiceClient grpcServiceClient;
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    private final BlockStreamStateManager blockStreamStateManager;

    // Locks and synchronization objects
    private final Object channelLock = new Object();
    private final Object workerLock = new Object();
    private final ReentrantLock isActiveLock = new ReentrantLock();

    // Atomic state variables
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean streamCompletionInProgress = new AtomicBoolean(false);
    private final AtomicLong currentBlockNumber = new AtomicLong(-1);
    private final AtomicInteger currentRequestIndex = new AtomicInteger(0);

    // Notification objects
    private final Object newBlockAvailable = new Object();
    private final Object newRequestAvailable = new Object();

    // Volatile connection state
    private volatile StreamObserver<PublishStreamRequest> requestObserver;
    private volatile Thread requestWorker;

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param nodeConfig the configuration for the block node
     * @param blockNodeConnectionManager the connection manager for block node connections
     * @param blockStreamStateManager the block stream state manager for block node connections
     * @param grpcServiceClient the gRPC client to establish the bidirectional streaming to block node connections
     */
    public BlockNodeConnection(
            @NonNull final BlockNodeConfig nodeConfig,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final BlockStreamStateManager blockStreamStateManager,
            @NonNull final GrpcServiceClient grpcServiceClient) {
        this.node = requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
        this.blockStreamStateManager =
                requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        this.grpcServiceClient = requireNonNull(grpcServiceClient, "grpcServiceClient must not be null");
    }

    /**
     * Establish the bidirectional streaming to block nodes.
     */
    public Void establishStream() {
        synchronized (isActiveLock) {
            synchronized (channelLock) {
                requestObserver = grpcServiceClient.bidi(blockNodeConnectionManager.getGrpcEndPoint(), this);
                isActive.set(true);
                startRequestWorker();
            }
        }
        return null;
    }

    private void startRequestWorker() {
        synchronized (workerLock) {
            if (requestWorker != null && requestWorker.isAlive()) {
                stopWorkerThread();
            }
            if (isActive.get()) {
                requestWorker = Thread.ofPlatform()
                        .name("BlockNodeConnection-RequestWorker-" + node.address() + ":" + node.port())
                        .start(this::requestWorkerLoop);
                logger.debug("Started request worker thread for block node {}:{}", node.address(), node.port());
            }
        }
    }

    private void requestWorkerLoop() {
        while (isActive.get()) {
            try {
                // Get the current block state
                final BlockState blockState = blockStreamStateManager.getBlockState(currentBlockNumber.get());

                // If block state is null, check if we're behind
                if (blockState == null && currentBlockNumber.get() != -1) {
                    long lowestAvailableBlock = blockStreamStateManager.getBlockNumber();
                    if (lowestAvailableBlock > currentBlockNumber.get()) {
                        logger.debug(
                                "[] Block {} state not found and lowest available block is {}, ending stream for node {}:{}",
                                currentBlockNumber.get(),
                                lowestAvailableBlock,
                                node.address(),
                                node.port());
                        handleStreamFailure();
                        return;
                    }
                }

                // Otherwise wait for new block if we're at -1 or the current block isn't available yet
                if (currentBlockNumber.get() == -1 || blockState == null) {
                    logger.debug(
                            "[] Waiting for new block to be available for node {}:{}", node.address(), node.port());
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
                        && currentRequestIndex.get() >= blockState.requests().size()) {
                    moveToNextBlock();
                }
            } catch (InterruptedException e) {
                logger.error("[] Request worker thread interrupted for node {}:{}", node.address(), node.port());
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.error("[] Error in request worker thread for node {}:{}", node.address(), node.port(), e);
                handleStreamFailure();
            }
        }
        logger.debug("[] Request worker thread exiting for node {}:{}", node.address(), node.port());
    }

    private void waitForNewBlock() throws InterruptedException {
        synchronized (newBlockAvailable) {
            newBlockAvailable.wait();
        }
    }

    private void waitForNewRequests() throws InterruptedException {
        logger.debug(
                "[] Waiting for new requests to be available for block {} on node {}:{}, "
                        + "currentRequestIndex: {}, requestsSize: {}",
                currentBlockNumber.get(),
                node.address(),
                node.port(),
                currentRequestIndex.get(),
                blockStreamStateManager.getBlockState(currentBlockNumber.get()) != null
                        ? blockStreamStateManager
                                .getBlockState(currentBlockNumber.get())
                                .requests()
                                .size()
                        : 0);
        synchronized (newRequestAvailable) {
            newRequestAvailable.wait();
        }
    }

    private void logBlockProcessingInfo(BlockState blockState) {
        logger.debug(
                "[] Processing block {} for node {}:{}, isComplete: {}, requests: {}",
                currentBlockNumber.get(),
                node.address(),
                node.port(),
                blockState.isComplete(),
                blockState.requests().size());
    }

    private boolean needToWaitForMoreRequests(@NonNull BlockState blockState) {
        return currentRequestIndex.get() >= blockState.requests().size() && !blockState.isComplete();
    }

    private void processAvailableRequests(@NonNull BlockState blockState) {
        synchronized (isActiveLock) {
            List<PublishStreamRequest> requests = blockState.requests();
            while (currentRequestIndex.get() < requests.size()) {
                if (!isActive.get()) {
                    return;
                }
                final PublishStreamRequest request = requests.get(currentRequestIndex.get());
                logger.debug(
                        "[] Sending request for block {} request index {} to node {}:{}, items: {}",
                        currentBlockNumber.get(),
                        currentRequestIndex.get(),
                        node.address(),
                        node.port(),
                        request.blockItems().blockItems().size());
                sendRequest(request);
                currentRequestIndex.incrementAndGet();
            }
        }
    }

    private void moveToNextBlock() {
        logger.debug(
                "[] Completed sending all requests for block {} to node {}:{}",
                currentBlockNumber.get(),
                node.address(),
                node.port());
        currentBlockNumber.incrementAndGet();
        currentRequestIndex.set(0);
    }

    private void handleStreamFailure() {
        synchronized (isActiveLock) {
            if (isActive.compareAndSet(true, false)) {
                synchronized (channelLock) {
                    if (requestObserver != null) {
                        try {
                            requestObserver.onCompleted();
                        } catch (Exception e) {
                            logger.warn("Error while completing request observer during stream failure", e);
                        }
                        requestObserver = null;
                    }
                }
                stopWorkerThread();
                removeFromActiveConnections(node);
                scheduleReconnect();
            }
        }
    }

    private void handleAcknowledgement(@NonNull PublishStreamResponse.Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            var blockAck = acknowledgement.blockAck();
            var blockNumber = blockAck.blockNumber();
            var blockAlreadyExists = blockAck.blockAlreadyExists();

            logger.debug(
                    "Received acknowledgement for block {} from node {}:{}, blockAlreadyExists: {}",
                    blockNumber,
                    node.address(),
                    node.port(),
                    blockAlreadyExists);

            // Remove all block states up to and including this block number
            blockStreamStateManager.removeBlockStatesUpTo(blockNumber);
        }
    }

    private void handleEndOfStream(@NonNull PublishStreamResponse.EndOfStream endOfStream) {
        var blockNumber = endOfStream.blockNumber();
        var responseCode = endOfStream.status();

        logger.debug(
                "[{}] Received EndOfStream from block node {}:{} at block {} with PublishStreamResponseCode {}",
                Thread.currentThread().getName(),
                node.address(),
                node.port(),
                blockNumber,
                responseCode);

        // For all error codes, restart after the last verified block + 1
        long restartBlockNumber = blockNumber == Long.MAX_VALUE ? 0 : blockNumber + 1;
        logger.debug(
                "[{}] Restarting stream at block {} due to {} for node {}:{}",
                Thread.currentThread().getName(),
                restartBlockNumber,
                responseCode,
                node.address(),
                node.port());
        endStreamAndRestartAtBlock(restartBlockNumber);
    }

    private void removeFromActiveConnections(BlockNodeConfig node) {
        blockNodeConnectionManager.disconnectFromNode(node);
    }

    /**
     * If connection is active sends a request to the block node, otherwise does nothing.
     *
     * @param request the request to send
     */
    public void sendRequest(@NonNull final PublishStreamRequest request) {
        requireNonNull(request);
        synchronized (isActiveLock) {
            synchronized (channelLock) {
                if (isActive.get() && requestObserver != null) {
                    requestObserver.onNext(request);
                }
            }
        }
    }

    private void scheduleReconnect() {
        logger.debug("Scheduling reconnect for block node {}:{}", node.address(), node.port());
        setCurrentBlockNumber(-1);
        blockNodeConnectionManager.scheduleReconnect(this);
    }

    /**
     * If connection is active it closes it, otherwise does nothing.
     */
    public void close() {
        synchronized (isActiveLock) {
            if (isActive.compareAndSet(true, false)) {
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
        }
        logger.debug("Closed connection to block node {}:{}", node.address(), node.port());
    }

    /**
     * Returns whether the connection is active.
     *
     * @return true if the connection is active, false otherwise
     */
    public boolean isActive() {
        return isActive.get();
    }

    /**
     * Returns the block node configuration this connection.
     *
     * @return the block node configuration
     */
    public BlockNodeConfig getNodeConfig() {
        return node;
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

    public ReentrantLock getIsActiveLock() {
        return isActiveLock;
    }

    public void notifyNewRequestAvailable() {
        synchronized (newRequestAvailable) {
            BlockState blockState = blockStreamStateManager.getBlockState(currentBlockNumber.get());
            if (blockState != null) {
                logger.debug(
                        "Notifying of new request available for node {}:{} - block: {}, requests: {}, isComplete: {}",
                        node.address(),
                        node.port(),
                        currentBlockNumber.get(),
                        blockState.requests().size(),
                        blockState.isComplete());
            } else {
                logger.debug(
                        "Notifying of new request available for node {}:{} - block: {} (state not found)",
                        node.address(),
                        node.port(),
                        currentBlockNumber.get());
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
                "Set current block number to {} for node {}:{}, reset request index to 0",
                blockNumber,
                node.address(),
                node.port());
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
                "Setting current block number to {} for node {}:{} without ending stream",
                blockNumber,
                node.address(),
                node.port());

        stopWorkerThread();
        setCurrentBlockNumber(blockNumber);
        startRequestWorker();
        notifyNewBlockAvailable();

        logger.debug(
                "Worker thread restarted and jumped to block {} for node {}:{}",
                blockNumber,
                node.address(),
                node.port());
    }

    /**
     * Ends the current stream and restarts a new stream at a specific block number.
     * This method will close the current connection, establish a new stream,
     * and start processing from the specified block number.
     *
     * @param blockNumber the block number to restart at
     */
    public void endStreamAndRestartAtBlock(long blockNumber) {
        logger.debug(
                "Ending stream and restarting at block {} for node {}:{}", blockNumber, node.address(), node.port());

        synchronized (isActiveLock) {
            synchronized (channelLock) {
                close();
                setCurrentBlockNumber(blockNumber);
                establishStream();
            }
        }

        logger.debug("Stream ended and restarted at block {} for node {}:{}", blockNumber, node.address(), node.port());
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
            handleEndOfStream(response.endStream());
        } else if (response.hasSkipBlock()) {
            logger.debug(
                    "Received SkipBlock from Block Node {}:{}  Block #{}",
                    node.address(),
                    node.port(),
                    response.skipBlock().blockNumber());
        } else if (response.hasResendBlock()) {
            logger.debug(
                    "Received ResendBlock from Block Node {}:{}  Block #{}",
                    node.address(),
                    node.port(),
                    response.resendBlock().blockNumber());
        }
    }

    @Override
    public void onError(Throwable error) {
        logger.error("[] Error on stream from block node {}:{}", node.address(), node.port(), error);
        handleStreamFailure();
    }

    @Override
    public void onCompleted() {
        if (streamCompletionInProgress.compareAndSet(false, true)) {
            try {
                logger.debug("[] Stream completed for block node {}:{}", node.address(), node.port());
                handleStreamFailure();
            } finally {
                streamCompletionInProgress.set(false);
            }
        }
    }
}
