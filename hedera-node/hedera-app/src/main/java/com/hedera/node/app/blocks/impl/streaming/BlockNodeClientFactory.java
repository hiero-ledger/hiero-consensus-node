// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.grpc.ServiceInterface.RequestOptions;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.spi.ProtocolConfig;
import java.time.Duration;
import java.util.Optional;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;

/**
 * Factory class to create instances of {@link BlockStreamPublishServiceClient} or {@link org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient} for communicating with block nodes.
 * This factory is necessary to test clients to mock the creation of the gRPC client. PBJ will create the underlying
 * connections in the constructor and there is no way to mock that.
 */
public class BlockNodeClientFactory {

    private static class DefaultRequestOptions implements ServiceInterface.RequestOptions {
        @Override
        public @NonNull Optional<String> authority() {
            return Optional.empty();
        }

        @Override
        public @NonNull String contentType() {
            return RequestOptions.APPLICATION_GRPC;
        }
    }

    private enum ClientType {
        STREAMING,
        SERVICE
    }

    /**
     * Create a new PBJ gRPC client using the specified configuration.
     *
     * @param config the block node configuration to use
     * @param timeout the timeout to use
     * @return a new {@link PbjGrpcClient} instance
     */
    private PbjGrpcClient buildPbjClient(
            @NonNull final ClientType clientType,
            @NonNull final BlockNodeConfiguration config,
            @NonNull final Duration timeout) {
        requireNonNull(config, "config is required");
        requireNonNull(timeout, "timeout is required");
        requireNonNull(clientType, "client type is required");

        final Tls tls = Tls.builder().enabled(false).build();
        final PbjGrpcClientConfig pbjConfig =
                new PbjGrpcClientConfig(timeout, tls, Optional.of(""), "application/grpc");
        final ProtocolConfig httpConfig = config.clientHttpConfig().toHttp2ClientProtocolConfig();
        final ProtocolConfig grpcConfig = config.clientGrpcConfig().toGrpcClientProtocolConfig();
        final int port =
                switch (clientType) {
                    case STREAMING -> config.streamingPort();
                    case SERVICE -> config.servicePort();
                };

        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + config.address() + ":" + port)
                .tls(tls)
                .addProtocolConfig(httpConfig)
                .addProtocolConfig(grpcConfig)
                .connectTimeout(timeout)
                .build();

        return new PbjGrpcClient(webClient, pbjConfig);
    }

    /**
     * Create a new {@link BlockStreamPublishServiceClient} instance using the specified configuration.
     *
     * @param config the block node configuration to use
     * @param timeout the timeout to use
     * @return a new {@link BlockStreamPublishServiceClient} instance
     */
    public BlockStreamPublishServiceClient createStreamingClient(
            @NonNull final BlockNodeConfiguration config, @NonNull final Duration timeout) {
        final PbjGrpcClient client = buildPbjClient(ClientType.STREAMING, config, timeout);
        return new BlockStreamPublishServiceClient(client, new DefaultRequestOptions());
    }

    /**
     * Create a new {@link BlockNodeServiceClient} instance using the specified configuration.
     *
     * @param config the block node configuration to use
     * @param timeout the timeout to use
     * @return a new {@link BlockNodeServiceClient} instance
     */
    public BlockNodeServiceClient createServiceClient(
            @NonNull final BlockNodeConfiguration config, @NonNull final Duration timeout) {
        final PbjGrpcClient client = buildPbjClient(ClientType.SERVICE, config, timeout);
        return new BlockNodeServiceClient(client, new DefaultRequestOptions());
    }
}
