// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import java.time.Duration;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.BlockStreamPublishServiceInterface.BlockStreamPublishServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeClientFactoryTest extends BlockNodeCommunicationTestBase {

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
                final BlockStreamPublishServiceClient client = factory.createStreamingClient(config, timeout)) {
            assertThat(client).isNotNull();

            assertThat(mockPbjClient.constructed()).hasSize(1);
        }
    }

    @Test
    void testCreateServiceClient() {
        try (final MockedConstruction<PbjGrpcClient> mockPbjClient = mockConstruction(PbjGrpcClient.class);
                final BlockNodeServiceClient client = factory.createServiceClient(config, timeout)) {
            assertThat(client).isNotNull();

            assertThat(mockPbjClient.constructed()).hasSize(1);
        }
    }
}
