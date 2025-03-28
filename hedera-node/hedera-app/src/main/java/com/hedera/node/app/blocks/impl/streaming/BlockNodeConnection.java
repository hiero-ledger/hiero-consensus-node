// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.blockNodeName;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Represents a single connection to a block node. Each connection is responsible for connecting to configured block nodes
 */
public class BlockNodeConnection implements StreamObserver<PublishStreamResponse> {
    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final BlockNodeConfig nodeConf;
    private ManagedChannel channel;
    private final BlockNodeConnectionManager manager;
    private StreamObserver<PublishStreamRequest> requestObserver;

    private final Object isActiveLock = new Object();
    private volatile boolean isActive = false;

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param nodeConfig the configuration for the block node
     * @param manager the connection manager for block node connections
     */
    public BlockNodeConnection(BlockNodeConfig nodeConfig, BlockNodeConnectionManager manager) {
        this.nodeConf = requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.manager = requireNonNull(manager, "manager must not be null");

        logger.info("BlockNodeConnection INITIALIZED");
    }

    public void establishStream() {
        if (requestObserver != null) {
            logger.info("Stream is already established for block node {}", blockNodeName(nodeConf));
            return;
        }

        this.channel = ManagedChannelBuilder.forAddress(nodeConf.address(), nodeConf.port())
                .usePlaintext() // 🔥🔥 For development only! change to use TLS in production 🔥🔥
                .build();
        BlockStreamServiceGrpc.BlockStreamServiceStub stub = BlockStreamServiceGrpc.newStub(channel);
        if (stub != null) {
            synchronized (isActiveLock) {
                requestObserver = stub.publishBlockStream(this);
                isActive = true;
                logger.info("BlockNodeConnection {} INITIALIZED", blockNodeName(nodeConf));
            }
        } else {
            logger.warn("Failed to create stub for block node {}", blockNodeName(nodeConf));
        }
    }

    private void handleAcknowledgement(PublishStreamResponse.Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            logger.info(
                    "Block acknowledgement received for block {}",
                    acknowledgement.getBlockAck().getBlockNumber());
            manager.handleBlockAck(this, acknowledgement.getBlockAck());
        } else if (logger.isWarnEnabled()) {
            logger.warn("Acknowledgement of unknown type received: {}", acknowledgement);
        }
    }

    private void handleStreamFailure(Throwable t) {
        synchronized (isActiveLock) {
            isActive = false;
        }

        final Status status = Status.fromThrowable(t);
        logger.error("Error in block stream to node {}: ({})", blockNodeName(getNodeConfig()), status, t);

        manager.handleConnectionError(this, t);
    }

    @Override
    public void onNext(PublishStreamResponse response) {
        if (response.hasAcknowledgement()) {
            handleAcknowledgement(response.getAcknowledgement());
        } else if (response.hasEndStream()) {
            handleEndOfStream(response.getEndStream());
        } else if (response.hasSkipBlock()) {
            logger.info(
                    "Received SkipBlock from Block Node {}  Block #{}",
                    blockNodeName(nodeConf),
                    response.getSkipBlock().getBlockNumber());
        } else if (response.hasResendBlock()) {
            logger.info(
                    "Received ResendBlock from Block Node {}  Block #{}",
                    blockNodeName(nodeConf),
                    response.getResendBlock().getBlockNumber());
        }
    }

    @Override
    public void onError(Throwable throwable) {
        handleStreamFailure(throwable);
    }

    @Override
    public void onCompleted() {
        handleGracefulClose();
    }

    private void handleEndOfStream(PublishStreamResponse.EndOfStream endOfStream) {
        isActive = false;
        logger.info(
                "Received end of stream status {} for block number {}",
                endOfStream.getStatus(),
                endOfStream.getBlockNumber());

        if (!endOfStream.getStatus().equals(PublishStreamResponseCode.STREAM_ITEMS_SUCCESS)) {
            manager.handleStreamError(this, endOfStream);
        } else {
            manager.handleEndOfStreamSuccess(this, endOfStream.getBlockNumber());
        }
    }

    private void handleGracefulClose() {
        isActive = false;
        logger.info("Received end of stream for block node {}", blockNodeName(nodeConf));

        manager.handleEndOfStreamSuccess(this);
    }

    /**
     * If connection is active sends a request to the block node, otherwise does nothing.
     *
     * @param request the request to send
     */
    public void sendRequest(@NonNull final PublishStreamRequest request) {
        requireNonNull(request);
        synchronized (isActiveLock) {
            if (isActive) {
                requestObserver.onNext(request);
            } else {
                logger.error("Cannot send request to block node {}: connection is not active", blockNodeName(nodeConf));
                throw new IllegalStateException("Connection is not active for node " + blockNodeName(nodeConf));
            }
        }
    }

    /**
     * Idempotent operation that closes this connection (if active)
     */
    public void close() {
        synchronized (isActiveLock) {
            if (isActive) {
                isActive = false;
                requestObserver.onCompleted();
                scheduler.shutdown();
                // Shutdown the channel gracefully
                try {
                    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    logger.warn("Channel shutdown interrupted", e);
                    Thread.currentThread().interrupt();
                } finally {
                    if (!channel.isShutdown()) {
                        channel.shutdownNow();
                    }
                }
            }
        }
    }

    /**
     * Returns whether the connection is active.
     *
     * @return true if the connection is active, false otherwise
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Returns the block node configuration for this connection.
     *
     * @return the block node configuration
     */
    public BlockNodeConfig getNodeConfig() {
        return nodeConf;
    }
}
