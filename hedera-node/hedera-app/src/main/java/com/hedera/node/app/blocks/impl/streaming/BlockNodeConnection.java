// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
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
public class BlockNodeConnection {
    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final BlockNodeConfig node;
    private final GrpcServiceClient grpcServiceClient;
    private final BlockNodeConnectionManager manager;
    private StreamObserver<PublishStreamRequest> requestObserver;
    private volatile boolean isActive = true;
    private final String connectionId;
    private final BlockAcknowledgementTracker acknowledgmentTracker;

    public BlockNodeConnection(
            @NonNull BlockNodeConfig nodeConfig,
            @NonNull GrpcServiceClient grpcServiceClient,
            @NonNull BlockNodeConnectionManager manager,
            @NonNull BlockAcknowledgementTracker acknowledgmentTracker) {
        this.node = requireNonNull(nodeConfig);
        this.grpcServiceClient = requireNonNull(grpcServiceClient);
        this.manager = requireNonNull(manager);
        this.acknowledgmentTracker = requireNonNull(acknowledgmentTracker);
        this.connectionId = generateConnectionId(nodeConfig);
        establishStream();
        logger.info("BlockNodeConnection INITIALIZED");
    }

    private void establishStream() {
        requestObserver =
                grpcServiceClient.bidi(manager.getGrpcEndPoint(), new StreamObserver<PublishStreamResponse>() {
                    @Override
                    public void onNext(PublishStreamResponse response) {
                        if (response.hasAcknowledgement()) {
                            handleAcknowledgement(response.getAcknowledgement());
                        } else if (response.hasStatus()) {
                            handleEndOfStream(response.getStatus());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        Status status = Status.fromThrowable(t);
                        logger.error("Error in block node stream {}:{}: {}", node.address(), node.port(), status, t);
                        handleStreamFailure();
                    }

                    @Override
                    public void onCompleted() {
                        logger.info("Stream completed for block node {}:{}", node.address(), node.port());
                        handleStreamFailure();
                    }
                });
    }

    private void handleAcknowledgement(PublishStreamResponse.Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            final var ackBlockNumber = acknowledgement.getBlockAck().getBlockNumber();

            acknowledgmentTracker.trackAcknowledgment(connectionId, ackBlockNumber);
        } else if (acknowledgement.hasItemAck()) {
            logger.info("Item acknowledgement received for a batch of block items: {}", acknowledgement.getItemAck());
        }
    }

    private void handleEndOfStream(PublishStreamResponse.EndOfStream endOfStream) {
        logger.info("Error returned from block node at block number {}: {}", endOfStream.getBlockNumber(), endOfStream);
    }

    private void removeFromActiveConnections(BlockNodeConfig node) {
        manager.handleConnectionError(node);
    }

    private String generateConnectionId(BlockNodeConfig nodeConfig) {
        return nodeConfig.address() + ":" + nodeConfig.port();
    }

    public void handleStreamFailure() {
        isActive = false;
        removeFromActiveConnections(node);
    }

    public void sendRequest(PublishStreamRequest request) {
        if (isActive) {
            requestObserver.onNext(request);
        }
    }

    public void close() {
        if (isActive) {
            isActive = false;
            requestObserver.onCompleted();
            scheduler.shutdown();
        }
    }

    public boolean isActive() {
        return isActive;
    }

    public BlockNodeConfig getNodeConfig() {
        return node;
    }
}
