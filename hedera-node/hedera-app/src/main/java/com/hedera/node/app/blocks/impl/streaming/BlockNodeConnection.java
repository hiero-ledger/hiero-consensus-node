// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager.blockNodeName;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.helidon.webclient.grpc.GrpcServiceClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Represents a single connection to a block node. Each connection is responsible for connecting to configured block nodes.
 */
public class BlockNodeConnection {
    private static final Logger logger = LogManager.getLogger(BlockNodeConnection.class);

    private final BlockNodeConfig nodeConf;
    private final GrpcServiceClient grpcServiceClient;
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    private StreamObserver<PublishStreamRequest> requestObserver;
    private volatile boolean isActive = false;

    /**
     * Construct a new BlockNodeConnection.
     *
     * @param nodeConfig the configuration for the block node
     * @param grpcServiceClient the gRPC service client
     * @param blockNodeConnectionManager the connection manager for block node connections
     */
    public BlockNodeConnection(
            @NonNull final BlockNodeConfig nodeConfig,
            @NonNull final GrpcServiceClient grpcServiceClient,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
        this.nodeConf = requireNonNull(nodeConfig, "nodeConfig must not be null");
        this.grpcServiceClient = requireNonNull(grpcServiceClient, "grpcServiceClient must not be null");
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
        logger.info("BlockNodeConnection {} INITIALIZED", blockNodeName(nodeConf));
    }

    public void establishStream() {
        if (requestObserver != null) {
            logger.error("Stream is already established for block node {}", blockNodeName(nodeConf));
            return;
        }

        requestObserver = grpcServiceClient.bidi(
                blockNodeConnectionManager.getGrpcEndPoint(), new StreamObserver<PublishStreamResponse>() {
                    @Override
                    public void onNext(PublishStreamResponse response) {
                        if (response.hasAcknowledgement()) {
                            handleAcknowledgement(response.getAcknowledgement());
                        } else if (response.hasEndStream()) {
                            handleEndOfStream(response.getEndStream());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        handleStreamFailure(t);
                    }

                    @Override
                    public void onCompleted() {
                        handleGracefulClose();
                    }
                });

        isActive = true;
    }

    private void handleAcknowledgement(PublishStreamResponse.Acknowledgement acknowledgement) {
        if (acknowledgement.hasBlockAck()) {
            logger.info(
                    "Block acknowledgement received for block {}",
                    acknowledgement.getBlockAck().getBlockNumber());
            blockNodeConnectionManager.handleBlockAck(this, acknowledgement.getBlockAck());
        } else if (logger.isWarnEnabled()) {
            logger.warn("Acknowledgement of unknown type received: {}", acknowledgement);
        }
    }

    private void handleStreamFailure(Throwable t) {
        isActive = false;

        final Status status = Status.fromThrowable(t);
        logger.error("Error in block stream to node {}: ({})", blockNodeName(getNodeConfig()), status, t);

        blockNodeConnectionManager.handleConnectionError(this, t);
    }

    private void handleEndOfStream(PublishStreamResponse.EndOfStream endOfStream) {
        isActive = false;
        logger.info(
                "Received end of stream status {} for block number {}",
                endOfStream.getStatus(),
                endOfStream.getBlockNumber());

        if (!endOfStream.getStatus().equals(PublishStreamResponseCode.STREAM_ITEMS_SUCCESS)) {
            blockNodeConnectionManager.handleStreamError(this, endOfStream);
        } else {
            blockNodeConnectionManager.handleEndOfStreamSuccess(this, endOfStream.getBlockNumber());
        }
    }

    private void handleGracefulClose() {
        isActive = false;
        logger.info("Received end of stream for block node {}", blockNodeName(nodeConf));

        blockNodeConnectionManager.handleEndOfStreamSuccess(this);
    }

    /**
     * If connection is active sends a request to the block node, otherwise does nothing.
     *
     * @param request the request to send
     */
    public void sendRequest(@NonNull final PublishStreamRequest request) {
        if (isActive) {
            requireNonNull(request);
            requestObserver.onNext(request);
        } else {
            logger.error("Cannot send request to block node {}: connection is not active", blockNodeName(nodeConf));
            throw new IllegalStateException("Connection is not active for node " + blockNodeName(nodeConf));
        }
    }

    /**
     * Idempotent operation that closes this connection (if active)
     */
    public void close() {
        if (isActive) {
            isActive = false;
            requestObserver.onCompleted();
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
