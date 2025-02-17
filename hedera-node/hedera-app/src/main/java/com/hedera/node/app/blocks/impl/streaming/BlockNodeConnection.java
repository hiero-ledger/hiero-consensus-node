/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.block.protoc.PublishStreamResponseCode.STREAM_ITEMS_UNKNOWN;

import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.node.internal.network.BlockNodeConfig;
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

    public BlockNodeConnection(
            BlockNodeConfig nodeConfig, GrpcServiceClient grpcServiceClient, BlockNodeConnectionManager manager) {
        this.node = nodeConfig;
        this.grpcServiceClient = grpcServiceClient;
        this.manager = manager;
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
            logger.info("Block acknowledgment received for a full block: {}", acknowledgement.getBlockAck());
        } else if (acknowledgement.hasItemAck()) {
            logger.info("Item acknowledgement received for a batch of block items: {}", acknowledgement.getItemAck());
        }
    }

    private void handleEndOfStream(PublishStreamResponse.EndOfStream endOfStream) {
        if (endOfStream.getStatus().equals(STREAM_ITEMS_UNKNOWN)) {
            logger.info(
                    "Error returned from block node at block number {}: {}", endOfStream.getBlockNumber(), endOfStream);
        }
    }

    private void removeFromActiveConnections(BlockNodeConfig node) {
        manager.handleConnectionError(node);
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
