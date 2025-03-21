// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
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

    private final BlockNodeConfig node;
    private ManagedChannel channel;
    private final BlockNodeConnectionManager manager;
    private StreamObserver<PublishStreamRequest> requestObserver;

    private final Object isActiveLock = new Object();
    private volatile boolean isActive = false;

    public BlockNodeConnection(BlockNodeConfig nodeConfig, BlockNodeConnectionManager manager) {
        this.node = nodeConfig;
        this.manager = manager;
    }

    public Void establishStream() {
        this.channel = ManagedChannelBuilder.forAddress(node.address(), node.port())
                .usePlaintext() // 🔥🔥 For development only! change to use TLS in production 🔥🔥
                .build();
        BlockStreamServiceGrpc.BlockStreamServiceStub stub = BlockStreamServiceGrpc.newStub(channel);
        synchronized (isActiveLock) {
            requestObserver = stub.publishBlockStream(this);
            isActive = true;
        }
        return null;
    }

    private void handleAcknowledgement(PublishStreamResponse.Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            logger.info(
                    "Block acknowledgment received for a full block: {}",
                    acknowledgement.getBlockAck().getBlockNumber());
        }
    }

    private void handleStreamFailure() {
        synchronized (isActiveLock) {
            isActive = false;
            removeFromActiveConnections(node);
        }
    }

    private void handleEndOfStream(PublishStreamResponse.EndOfStream endOfStream) {
        logger.info("Error returned from block node at block number {}: {}", endOfStream.getBlockNumber(), endOfStream);
    }

    private void removeFromActiveConnections(BlockNodeConfig node) {
        manager.handleConnectionError(node);
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
            }
        }
    }

    private void scheduleReconnect() {
        manager.scheduleReconnect(this);
    }

    /**
     * If connection is active it closes it, otherwise does nothing.
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
     * Returns the block node configuration this connection.
     *
     * @return the block node configuration
     */
    public BlockNodeConfig getNodeConfig() {
        return node;
    }

    @Override
    public void onNext(PublishStreamResponse response) {
        if (response.hasAcknowledgement()) {
            handleAcknowledgement(response.getAcknowledgement());
        } else if (response.hasEndStream()) {
            handleEndOfStream(response.getEndStream());
        } else if (response.hasSkipBlock()) {
            logger.info(
                    "Received SkipBlock from Block Node {}:{}  Block #{}",
                    node.address(),
                    node.port(),
                    response.getSkipBlock().getBlockNumber());
        } else if (response.hasResendBlock()) {
            logger.info(
                    "Received ResendBlock from Block Node {}:{}  Block #{}",
                    node.address(),
                    node.port(),
                    response.getResendBlock().getBlockNumber());
        }
    }

    @Override
    public void onError(Throwable throwable) {
        Status status = Status.fromThrowable(throwable);
        logger.error(
                "Error in block node stream {}:{}: {} {}", node.address(), node.port(), status, throwable.getMessage());
        handleStreamFailure();
        scheduleReconnect();
    }

    @Override
    public void onCompleted() {
        logger.info("Stream completed for block node {}:{}", node.address(), node.port());
        handleStreamFailure();
    }
}
