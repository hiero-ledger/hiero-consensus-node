// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.PublishStreamRequestBytes;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.runtime.grpc.GrpcCall;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.block.api.BlockStreamPublishServiceInterface;
import org.hiero.block.api.PublishStreamResponse;

/**
 * A thin gRPC client that publishes a block stream to a block node using {@link PublishStreamRequestBytes}, whose block
 * items are carried as already-serialized bytes.
 * <p>
 * It targets the exact same gRPC method as the PBJ-generated {@code BlockStreamPublishServiceClient}
 * ({@value #PUBLISH_BLOCK_STREAM_METHOD}). Because a {@link PublishStreamRequestBytes} serializes byte-identically to
 * the equivalent {@code org.hiero.block.api.PublishStreamRequest}, the block node requires no changes and is unaware
 * that a different request type was used on this side.
 */
public final class BlockStreamPublishBytesClient implements AutoCloseable {
    /** Full gRPC method name (fully-qualified service name + method) of the block node publish-stream RPC. */
    static final String PUBLISH_BLOCK_STREAM_METHOD =
            BlockStreamPublishServiceInterface.FULL_NAME + "/publishBlockStream";

    private final PbjGrpcClient grpcClient;
    private final ServiceInterface.RequestOptions requestOptions;

    /**
     * Construct a new client. Package-private because the {@link PbjGrpcClient} dependency is an internal
     * implementation detail of this package (it is not part of the module's exported API).
     *
     * @param grpcClient the underlying PBJ gRPC client (owns the network connection)
     * @param requestOptions the request options (e.g. correlation-id metadata) to send with the stream
     */
    BlockStreamPublishBytesClient(
            @NonNull final PbjGrpcClient grpcClient, @NonNull final ServiceInterface.RequestOptions requestOptions) {
        this.grpcClient = requireNonNull(grpcClient, "grpcClient must not be null");
        this.requestOptions = requireNonNull(requestOptions, "requestOptions must not be null");
    }

    /**
     * Opens a bidirectional publish stream. Requests are sent via the returned call; responses from the block node are
     * routed to the supplied pipeline.
     *
     * @param replies the pipeline that will receive {@link PublishStreamResponse}s from the block node
     * @return the gRPC call used to send {@link PublishStreamRequestBytes}s
     */
    @NonNull
    public GrpcCall<PublishStreamRequestBytes, PublishStreamResponse> publishBlockStream(
            @NonNull final Pipeline<PublishStreamResponse> replies) {
        requireNonNull(replies, "replies must not be null");
        return grpcClient.createCall(
                PUBLISH_BLOCK_STREAM_METHOD,
                PublishStreamRequestBytes.PROTOBUF,
                PublishStreamResponse.PROTOBUF,
                replies,
                requestOptions.metadata());
    }

    @Override
    public void close() {
        grpcClient.close();
    }
}
