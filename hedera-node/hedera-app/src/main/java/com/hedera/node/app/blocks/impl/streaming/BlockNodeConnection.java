// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.PublishStreamResponse;
import com.hedera.hapi.block.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.PublishStreamResponse.EndOfStream;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Represents a single connection to a block node. Each connection is responsible for connecting to configured block nodes
 */
public class BlockNodeConnection implements StreamObserver<PublishStreamResponse> {
    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final BlockNodeConfig node;
    private final GrpcServiceClient grpcServiceClient;
    private final BlockNodeConnectionManager manager;
    private StreamObserver<PublishStreamRequest> requestObserver;

    private final Object isActiveLock = new Object();
    private volatile boolean isActive = false;

    public BlockNodeConnection(
            BlockNodeConfig nodeConfig, GrpcServiceClient grpcServiceClient, BlockNodeConnectionManager manager) {
        this.node = nodeConfig;
        this.grpcServiceClient = grpcServiceClient;
        this.manager = manager;
    }

    public Void establishStream() {
        synchronized (isActiveLock) {
            requestObserver = grpcServiceClient.bidi(manager.getGrpcEndPoint(), this);
            isActive = true;
        }
        return null;
    }

    private void handleAcknowledgement(Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            logger.debug("Block acknowledgment received for a full block: {}", acknowledgement.blockAck());
        }
    }

    private void handleStreamFailure() {
        synchronized (isActiveLock) {
            isActive = false;
            removeFromActiveConnections(node);
        }
    }

    private void handleEndOfStream(EndOfStream endOfStream) {
        logger.debug("Error returned from block node at block number {}: {}", endOfStream.blockNumber(), endOfStream);
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
    public void onError(Throwable throwable) {
        Status status = Status.fromThrowable(throwable);
        logger.error(
                "Error in block node stream {}:{}: {} {}", node.address(), node.port(), status, throwable.getMessage());
        handleStreamFailure();
        scheduleReconnect();
    }

    @Override
    public void onCompleted() {
        logger.debug("Stream completed for block node {}:{}", node.address(), node.port());
        handleStreamFailure();
    }
}
