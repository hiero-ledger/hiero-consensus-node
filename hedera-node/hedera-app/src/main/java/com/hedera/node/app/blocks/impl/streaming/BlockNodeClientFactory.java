// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.webclient.api.WebClient;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;

/**
 * Factory class to create instances of {@link BlockStreamPublishServiceClient} for communicating with block nodes.
 * This factory is necessary to test clients to mock the creation of the gRPC client. PBJ will create the underlying
 * connections in the constructor and there is no way to mock that.
 */
public class BlockNodeClientFactory {

    /**
     * Container for both gRPC service clients that share the same underlying connection.
     */
    public record BlockNodeClients(
            @NonNull PbjGrpcClient grpcClient,
            @NonNull BlockStreamPublishServiceClient publishServiceClient,
            @NonNull BlockNodeServiceClient blockNodeServiceClient) {}

    /**
     * Creates both service clients (publish and block node) that share the same underlying gRPC connection.
     * @param webClient the Helidon WebClient to be used for communication
     * @param config the gRPC client configuration
     * @param requestOptions the request options for the service interface
     * @return a container with both service clients
     */
    public BlockNodeClients createClients(
            @NonNull final WebClient webClient,
            @NonNull final PbjGrpcClientConfig config,
            @NonNull final ServiceInterface.RequestOptions requestOptions) {
        final PbjGrpcClient grpcClient = new PbjGrpcClient(webClient, config);
        final BlockStreamPublishServiceClient publishServiceClient =
                new BlockStreamPublishServiceClient(grpcClient, requestOptions);
        final BlockNodeServiceClient blockNodeServiceClient = new BlockNodeServiceClient(grpcClient, requestOptions);
        return new BlockNodeClients(grpcClient, publishServiceClient, blockNodeServiceClient);
    }
}
