// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.internal.network.HelidonGrpcConfig;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BlockNodeHelidonGrpcConfigurationTest {

    @Test
    void testBuilder() {
        final boolean abortPollTimeExpired = true;
        final Duration heartbeatPeriod = Duration.ofSeconds(10);
        final int initialBufferSize = 3_000;
        final String name = "foo";
        final Duration pollWaitTime = Duration.ofSeconds(5);

        final BlockNodeHelidonGrpcConfiguration grpcConfig = BlockNodeHelidonGrpcConfiguration.newBuilder()
                .abortPollTimeExpired(abortPollTimeExpired)
                .heartbeatPeriod(heartbeatPeriod)
                .initialBufferSize(initialBufferSize)
                .name(name)
                .pollWaitTime(pollWaitTime)
                .build();

        assertThat(grpcConfig.abortPollTimeExpired()).hasValue(abortPollTimeExpired);
        assertThat(grpcConfig.heartbeatPeriod()).hasValue(heartbeatPeriod);
        assertThat(grpcConfig.initialBufferSize()).hasValue(initialBufferSize);
        assertThat(grpcConfig.name()).hasValue(name);
        assertThat(grpcConfig.pollWaitTime()).hasValue(pollWaitTime);
    }

    @Test
    void testFromHelidonGrpcConfig_nullInput() {
        final BlockNodeHelidonGrpcConfiguration httpConfig = BlockNodeHelidonGrpcConfiguration.from(null);
        assertThat(httpConfig).isNotNull().isEqualTo(BlockNodeHelidonGrpcConfiguration.DEFAULT);
    }

    @Test
    void testFromHelidonGrpcConfig() {
        final HelidonGrpcConfig helidonGrpcConfig = HelidonGrpcConfig.newBuilder()
                .abortPollTimeExpired(true)
                .heartbeatPeriod("PT10S")
                .initBufferSize(3_000)
                .name("foo")
                .pollWaitTime("PT3S")
                .build();

        final BlockNodeHelidonGrpcConfiguration grpcConfig = BlockNodeHelidonGrpcConfiguration.from(helidonGrpcConfig);

        assertThat(grpcConfig.abortPollTimeExpired()).hasValue(true);
        assertThat(grpcConfig.heartbeatPeriod()).hasValue(Duration.ofSeconds(10));
        assertThat(grpcConfig.initialBufferSize()).hasValue(3_000);
        assertThat(grpcConfig.name()).hasValue("foo");
        assertThat(grpcConfig.pollWaitTime()).hasValue(Duration.ofSeconds(3));
    }

    @Test
    void testToGrpcClientProtocolConfig() {
        final boolean abortPollTimeExpired = true;
        final Duration heartbeatPeriod = Duration.ofSeconds(10);
        final int initialBufferSize = 3_000;
        final String name = "foo";
        final Duration pollWaitTime = Duration.ofSeconds(5);

        final BlockNodeHelidonGrpcConfiguration grpcConfig = BlockNodeHelidonGrpcConfiguration.newBuilder()
                .abortPollTimeExpired(abortPollTimeExpired)
                .heartbeatPeriod(heartbeatPeriod)
                .initialBufferSize(initialBufferSize)
                .name(name)
                .pollWaitTime(pollWaitTime)
                .build();
        final GrpcClientProtocolConfig protocolConfig = grpcConfig.toGrpcClientProtocolConfig();

        assertThat(protocolConfig.abortPollTimeExpired()).isEqualTo(abortPollTimeExpired);
        assertThat(protocolConfig.heartbeatPeriod()).isEqualTo(heartbeatPeriod);
        assertThat(protocolConfig.initBufferSize()).isEqualTo(initialBufferSize);
        assertThat(protocolConfig.name()).isEqualTo(name);
        assertThat(protocolConfig.pollWaitTime()).isEqualTo(pollWaitTime);
    }
}
