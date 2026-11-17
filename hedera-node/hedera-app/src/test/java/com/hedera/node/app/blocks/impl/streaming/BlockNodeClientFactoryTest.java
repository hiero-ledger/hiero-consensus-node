// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeTlsConfiguration;
import com.hedera.node.config.types.BlockStreamGrpcCompressionType;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import io.helidon.webclient.api.WebClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeClientFactoryTest extends BlockNodeCommunicationTestBase {

    private static final String FINGERPRINT = "a".repeat(96);

    private BlockNodeClientFactory factory;
    private BlockNodeConfiguration config;
    private Duration timeout;

    @BeforeEach
    void beforeEach() {
        config = newBlockNodeConfig(8180, 2);
        timeout = Duration.ofSeconds(2);

        factory = new BlockNodeClientFactory();
    }

    @Test
    void testCreateStreamingClient() {
        try (final MockedConstruction<PbjGrpcClient> mockPbjClient = mockConstruction(PbjGrpcClient.class);
                final BlockStreamPublishBytesClient client =
                        factory.createStreamingClient(config, timeout, BlockStreamGrpcCompressionType.NONE)) {
            assertThat(client).isNotNull();

            assertThat(mockPbjClient.constructed()).hasSize(1);
        }
    }

    @Test
    void testCreateServiceClient() {
        try (final MockedConstruction<PbjGrpcClient> mockPbjClient = mockConstruction(PbjGrpcClient.class);
                final BlockNodeServiceClient client =
                        factory.createServiceClient(config, timeout, BlockStreamGrpcCompressionType.NONE)) {
            assertThat(client).isNotNull();

            assertThat(mockPbjClient.constructed()).hasSize(1);
        }
    }

    @Test
    void testPlaintextByDefault() {
        assertClient(
                f -> f.createStreamingClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "http",
                false);
        assertClient(
                f -> f.createServiceClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "http",
                false);
    }

    @Test
    void testTlsOnPublishApiOnly() {
        config = newBlockNodeConfig("localhost", 8180, 8181, 2, tls(true), BlockNodeTlsConfiguration.DISABLED);

        assertClient(
                f -> f.createStreamingClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "https",
                true);
        assertClient(
                f -> f.createServiceClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "http",
                false);
    }

    @Test
    void testTlsOnServiceApiOnly() {
        config = newBlockNodeConfig("localhost", 8180, 8181, 2, BlockNodeTlsConfiguration.DISABLED, tls(true));

        assertClient(
                f -> f.createStreamingClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "http",
                false);
        assertClient(
                f -> f.createServiceClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "https",
                true);
    }

    @Test
    void testTlsOnAllApis() {
        config = newBlockNodeConfig("localhost", 8180, 8181, 2, tls(true), tls(true));

        assertClient(
                f -> f.createStreamingClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "https",
                true);
        assertClient(
                f -> f.createServiceClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "https",
                true);
    }

    @Test
    void testPinnedCertificateTls() {
        config = newBlockNodeConfig("localhost", 8180, 8181, 2, tls(false), BlockNodeTlsConfiguration.DISABLED);

        assertClient(
                f -> f.createStreamingClient(config, timeout, BlockStreamGrpcCompressionType.NONE)
                        .close(),
                "https",
                true);
    }

    /**
     * Creates a client via the given action and asserts the URI scheme and TLS state of the web client the factory
     * handed to the underlying PBJ client.
     *
     * @param action the factory call under test
     * @param expectedScheme the URI scheme the client should target
     * @param tlsEnabled whether the client should negotiate TLS
     */
    private void assertClient(
            final Consumer<BlockNodeClientFactory> action, final String expectedScheme, final boolean tlsEnabled) {
        final List<WebClient> webClients = new ArrayList<>();
        try (final MockedConstruction<PbjGrpcClient> ignored = mockConstruction(
                PbjGrpcClient.class,
                (mock, ctx) -> webClients.add((WebClient) ctx.arguments().getFirst()))) {
            action.accept(factory);
        }

        assertThat(webClients).hasSize(1);
        final WebClient webClient = webClients.getFirst();
        assertThat(webClient.prototype().baseUri().orElseThrow().scheme()).isEqualTo(expectedScheme);
        assertThat(webClient.prototype().tls().enabled()).isEqualTo(tlsEnabled);
    }

    private static BlockNodeTlsConfiguration tls(final boolean useTrustStore) {
        final BlockNodeTlsConfiguration.Builder builder =
                BlockNodeTlsConfiguration.newBuilder().enabled(true);
        return useTrustStore
                ? builder.build()
                : builder.certificateSha384(FINGERPRINT).build();
    }
}
