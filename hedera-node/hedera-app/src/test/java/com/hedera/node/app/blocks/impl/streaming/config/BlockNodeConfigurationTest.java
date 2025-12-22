// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.HelidonGrpcConfig;
import com.hedera.node.internal.network.HelidonHttpConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BlockNodeConfigurationTest {

    @Test
    void testNullAddress() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Address must be specified");
    }

    @Test
    void testEmptyAddress() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("      ")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Address must not be empty");
    }

    @Test
    void testBadStreamingPort() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(0)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Streaming port must be greater than or equal to 1");
    }

    @Test
    void testBadServicePort() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(0)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Service port must be greater than or equal to 1");
    }

    @Test
    void testDefaultServicePort() {
        final BlockNodeConfiguration config = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT)
                .build();

        assertThat(config.streamingPort()).isEqualTo(8080);
        assertThat(config.servicePort()).isEqualTo(8080); // defaults to same as streaming port
    }

    @Test
    void testBadPriority() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(-10)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Priority must be greater than or equal to 0");
    }

    @Test
    void testBadSoftLimitSize() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(0)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message size soft limit must be greater than 0");
    }

    @Test
    void testBadHardLimitSize() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(100)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message size hard limit must be greater than or equal to soft limit size");
    }

    @Test
    void testMissingClientHttpConfig() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Client HTTP config must be specified");
    }

    @Test
    void testMissingClientGrpcConfig() {
        final BlockNodeConfiguration.Builder builder = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT);

        assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Client gRPC config must be specified");
    }

    @Test
    void testBuilder() {
        final BlockNodeHelidonHttpConfiguration clientHttpConfig =
                BlockNodeHelidonHttpConfiguration.newBuilder().build();
        final BlockNodeHelidonGrpcConfiguration clientGrpcConfig =
                BlockNodeHelidonGrpcConfiguration.newBuilder().build();

        final BlockNodeConfiguration config = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(1)
                .messageSizeSoftLimitBytes(2_000_000)
                .messageSizeHardLimitBytes(6_000_000)
                .clientHttpConfig(clientHttpConfig)
                .clientGrpcConfig(clientGrpcConfig)
                .build();

        assertThat(config).isNotNull();
        assertThat(config.address()).isEqualTo("localhost");
        assertThat(config.streamingPort()).isEqualTo(8080);
        assertThat(config.servicePort()).isEqualTo(8081);
        assertThat(config.priority()).isEqualTo(1);
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_000_000);
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_000_000);
        assertThat(config.clientGrpcConfig()).isEqualTo(clientGrpcConfig);
        assertThat(config.clientHttpConfig()).isEqualTo(clientHttpConfig);
    }

    @Test
    void testFromBlockNodeConfig_nullInput() {
        assertThatThrownBy(() -> BlockNodeConfiguration.from(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("config must be specified");
    }

    @Test
    void testFromBlockNodeConfig() {
        final BlockNodeConfig cfg = BlockNodeConfig.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(1)
                .messageSizeSoftLimitBytes(2_000_000L)
                .messageSizeHardLimitBytes(6_000_000L)
                .clientHttpConfig(HelidonHttpConfig.newBuilder()
                        .flowControlBlockTimeout("PT5S")
                        .initialWindowSize(32_000)
                        .maxFrameSize(16_000)
                        .maxHeaderListSize(1_500_000L)
                        .name("http-config")
                        .ping(true)
                        .pingTimeout("PT3S")
                        .priorKnowledge(true)
                        .build())
                .clientGrpcConfig(HelidonGrpcConfig.newBuilder()
                        .abortPollTimeExpired(true)
                        .heartbeatPeriod("PT2S")
                        .initBufferSize(32_000)
                        .name("grpc-config")
                        .pollWaitTime("PT5S")
                        .build())
                .build();

        final BlockNodeConfiguration config = BlockNodeConfiguration.from(cfg);
        assertThat(config.address()).isEqualTo("localhost");
        assertThat(config.streamingPort()).isEqualTo(8080);
        assertThat(config.servicePort()).isEqualTo(8081);
        assertThat(config.priority()).isEqualTo(1);
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_000_000L);
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_000_000L);

        final BlockNodeHelidonGrpcConfiguration clientGrpcConfig = config.clientGrpcConfig();
        assertThat(clientGrpcConfig).isNotNull();
        assertThat(clientGrpcConfig.abortPollTimeExpired()).hasValue(true);
        assertThat(clientGrpcConfig.heartbeatPeriod()).hasValue(Duration.ofSeconds(2));
        assertThat(clientGrpcConfig.initialBufferSize()).hasValue(32_000);
        assertThat(clientGrpcConfig.name()).hasValue("grpc-config");
        assertThat(clientGrpcConfig.pollWaitTime()).hasValue(Duration.ofSeconds(5));

        final BlockNodeHelidonHttpConfiguration clientHttpConfig = config.clientHttpConfig();
        assertThat(clientHttpConfig).isNotNull();
        assertThat(clientHttpConfig.flowControlBlockTimeout()).hasValue(Duration.ofSeconds(5));
        assertThat(clientHttpConfig.initialWindowSize()).hasValue(32_000);
        assertThat(clientHttpConfig.maxFrameSize()).hasValue(16_000);
        assertThat(clientHttpConfig.maxHeaderListSize()).hasValue(1_500_000L);
        assertThat(clientHttpConfig.name()).hasValue("http-config");
        assertThat(clientHttpConfig.ping()).hasValue(true);
        assertThat(clientHttpConfig.pingTimeout()).hasValue(Duration.ofSeconds(3));
        assertThat(clientHttpConfig.priorKnowledge()).hasValue(true);
    }
}
