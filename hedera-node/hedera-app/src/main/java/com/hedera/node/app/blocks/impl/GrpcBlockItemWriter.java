/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.protoc.PublishStreamResponseCode.STREAM_ITEMS_UNKNOWN;
import static io.grpc.Status.fromThrowable;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.block.protoc.BlockItemSet;
import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.protoc.PublishStreamResponse.EndOfStream;
import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.hapi.block.stream.protoc.BlockItem;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.grpc.GrpcClient;
import io.helidon.webclient.grpc.GrpcClientMethodDescriptor;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.grpc.GrpcServiceClient;
import io.helidon.webclient.grpc.GrpcServiceDescriptor;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implements the bidirectional streaming RPC for the publishBlockStream rpc in BlockStreamService
 * See <a href="https://grpc.io/docs/languages/java/basics/">gRPC Basics</a>
 */
public class GrpcBlockItemWriter implements BlockItemWriter {

    private static final Logger logger = LogManager.getLogger(GrpcBlockItemWriter.class);
    private static final String INVALID_MESSAGE = "Invalid protocol buffer converting %s from PBJ to protoc for %s";
    private static final String GRPC_END_POINT =
            BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();

    private StreamObserver<PublishStreamRequest> requestObserver;
    private final GrpcServiceClient grpcServiceClient;
    private long blockNumber;

    /** The state of this writer */
    private State state = State.UNINITIALIZED;

    /**
     * The current state of the gRPC writer.
     */
    public enum State {
        /**
         * The gRPC client is not initialized.
         */
        UNINITIALIZED,
        /**
         * The gRPC client is currently open and blocks can be streamed.
         */
        OPEN,
        /**
         * The gRPC client is already closed and cannot be used to stream blocks.
         */
        CLOSED
    }

    /**
     * @param blockStreamConfig the block stream configuration
     */
    public GrpcBlockItemWriter(@NonNull final BlockStreamConfig blockStreamConfig) {
        requireNonNull(blockStreamConfig, "The supplied argument 'blockStreamConfig' cannot be null!");
        GrpcClient client;
        try {
            client = GrpcClient.builder()
                    .tls(Tls.builder().enabled(false).build())
                    .baseUri(new URI(
                            null,
                            null,
                            blockStreamConfig.grpcAddress(),
                            blockStreamConfig.grpcPort(),
                            null,
                            null,
                            null))
                    .protocolConfig(GrpcClientProtocolConfig.builder()
                            .abortPollTimeExpired(false)
                            .build())
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        grpcServiceClient = client.serviceClient(GrpcServiceDescriptor.builder()
                .serviceName(BlockStreamServiceGrpc.SERVICE_NAME)
                .putMethod(
                        GRPC_END_POINT,
                        GrpcClientMethodDescriptor.bidirectional(BlockStreamServiceGrpc.SERVICE_NAME, GRPC_END_POINT)
                                .requestType(PublishStreamRequest.class)
                                .responseType(PublishStreamResponse.class)
                                .build())
                .build());
    }

    @Override
    public void openBlock(long blockNumber) {
        if (state != State.UNINITIALIZED) throw new IllegalStateException("GrpcBlockItemWriter initialized twice");

        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");
        this.blockNumber = blockNumber;
        requestObserver = grpcServiceClient.bidi(GRPC_END_POINT, new StreamObserver<PublishStreamResponse>() {
            @Override
            public void onNext(PublishStreamResponse streamResponse) {
                if (streamResponse.hasAcknowledgement()) {
                    final Acknowledgement acknowledgement = streamResponse.getAcknowledgement();
                    if (acknowledgement.hasBlockAck()) {
                        logger.info("PublishStreamResponse: a full block received: {}", acknowledgement.getBlockAck());
                    } else if (acknowledgement.hasItemAck()) {
                        logger.info(
                                "PublishStreamResponse: a single block item is received: {}",
                                acknowledgement.getItemAck());
                    }
                } else if (streamResponse.hasStatus()) {
                    final EndOfStream endOfStream = streamResponse.getStatus();
                    if (endOfStream.getStatus().equals(STREAM_ITEMS_UNKNOWN)) {
                        logger.info(
                                "Error returned from block node at block number {}: {}",
                                endOfStream.getBlockNumber(),
                                endOfStream);
                        onNext(buildErrorResponse(STREAM_ITEMS_UNKNOWN));
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                // Maybe this should be considered in this case:
                // https://github.com/hashgraph/hedera-services/issues/15530
                final Status status = fromThrowable(t);
                logger.error("error occurred with an exception: ", status.toString());
                requestObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                logger.info("PublishStreamResponse completed");
                requestObserver.onCompleted();
            }
        });
        this.state = State.OPEN;
    }

    @Override
    public BlockItemWriter writeItem(@NonNull final byte[] bytes) {
        requireNonNull(bytes);
        if (state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot write to a GrpcBlockItemWriter that is not open for block: " + this.blockNumber);
        }

        PublishStreamRequest request = PublishStreamRequest.newBuilder().build();
        try {
            BlockItemSet items = BlockItemSet.newBuilder()
                    .addBlockItems(BlockItem.parseFrom(bytes))
                    .build();
            request = PublishStreamRequest.newBuilder().setBlockItems(items).build();
            requestObserver.onNext(request);
        } catch (IOException e) {
            final String message = INVALID_MESSAGE.formatted("PublishStreamResponse", request);
            throw new RuntimeException(message, e);
        }
        return this;
    }

    @Override
    public BlockItemWriter writeItems(@NonNull BufferedData data) {
        requireNonNull(data);
        if (state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot write to a GrpcBlockItemWriter that is not open for block: " + this.blockNumber);
        }

        PublishStreamRequest request = PublishStreamRequest.newBuilder().build();
        try {
            BlockItemSet items = BlockItemSet.newBuilder()
                    .addBlockItems(BlockItem.parseFrom(data.asInputStream()))
                    .build();
            request = PublishStreamRequest.newBuilder().setBlockItems(items).build();
            requestObserver.onNext(request);
        } catch (IOException e) {
            final String message = INVALID_MESSAGE.formatted("PublishStreamResponse", request);
            throw new RuntimeException(message, e);
        }
        return this;
    }

    @Override
    public void closeBlock() {
        if (state.ordinal() < State.OPEN.ordinal()) {
            throw new IllegalStateException("Cannot close a GrpcBlockItemWriter that is not open");
        } else if (state.ordinal() == State.CLOSED.ordinal()) {
            throw new IllegalStateException("Cannot close a GrpcBlockItemWriter that is already closed");
        }

        requestObserver.onCompleted();
        this.state = State.CLOSED;
    }

    /**
     * @return the current state of the gRPC writer
     */
    @VisibleForTesting
    public long getBlockNumber() {
        return blockNumber;
    }

    /**
     * @return the current state of the gRPC writer
     */
    @VisibleForTesting
    public State getState() {
        return state;
    }

    /**
     * @param errorCode the error code for the stream response
     * @return the error stream response
     */
    private PublishStreamResponse buildErrorResponse(PublishStreamResponseCode errorCode) {
        final EndOfStream endOfStream =
                EndOfStream.newBuilder().setStatus(errorCode).build();
        return PublishStreamResponse.newBuilder().setStatus(endOfStream).build();
    }
}
