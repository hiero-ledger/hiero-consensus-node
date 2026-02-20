// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.internal.network.HelidonHttpConfig;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BlockNodeHelidonHttpConfigurationTest {

    @Test
    void testBuilder() {
        final Duration flowControlBlockTimeout = Duration.ofSeconds(10);
        final int initialWindowSize = 15_000;
        final int maxFrameSize = 32_768;
        final long maxHeaderListSize = 1_500_000;
        final String name = "foo";
        final boolean ping = true;
        final Duration pingTimeout = Duration.ofSeconds(3);
        final boolean priorKnowledge = true;

        final BlockNodeHelidonHttpConfiguration httpConfig = BlockNodeHelidonHttpConfiguration.newBuilder()
                .flowControlBlockTimeout(flowControlBlockTimeout)
                .initialWindowSize(initialWindowSize)
                .maxFrameSize(maxFrameSize)
                .maxHeaderListSize(maxHeaderListSize)
                .name(name)
                .ping(ping)
                .pingTimeout(pingTimeout)
                .priorKnowledge(priorKnowledge)
                .build();

        assertThat(httpConfig.flowControlBlockTimeout()).hasValue(flowControlBlockTimeout);
        assertThat(httpConfig.initialWindowSize()).hasValue(initialWindowSize);
        assertThat(httpConfig.maxFrameSize()).hasValue(maxFrameSize);
        assertThat(httpConfig.maxHeaderListSize()).hasValue(maxHeaderListSize);
        assertThat(httpConfig.name()).hasValue(name);
        assertThat(httpConfig.ping()).hasValue(ping);
        assertThat(httpConfig.pingTimeout()).hasValue(pingTimeout);
        assertThat(httpConfig.priorKnowledge()).hasValue(priorKnowledge);
    }

    @Test
    void testFromHelidonHttpConfig_nullInput() {
        final BlockNodeHelidonHttpConfiguration httpConfig = BlockNodeHelidonHttpConfiguration.from(null);
        assertThat(httpConfig).isNotNull().isEqualTo(BlockNodeHelidonHttpConfiguration.DEFAULT);
    }

    @Test
    void testFromHelidonHttpConfig() {
        final HelidonHttpConfig helidonHttpConfig = HelidonHttpConfig.newBuilder()
                .flowControlBlockTimeout("PT15S")
                .initialWindowSize(15_000)
                .maxFrameSize(32_768)
                .maxHeaderListSize(1_500_000L)
                .name("foo")
                .ping(true)
                .pingTimeout("PT3S")
                .priorKnowledge(true)
                .build();

        final BlockNodeHelidonHttpConfiguration httpConfig = BlockNodeHelidonHttpConfiguration.from(helidonHttpConfig);

        assertThat(httpConfig.flowControlBlockTimeout()).hasValue(Duration.ofSeconds(15));
        assertThat(httpConfig.initialWindowSize()).hasValue(15_000);
        assertThat(httpConfig.maxFrameSize()).hasValue(32_768);
        assertThat(httpConfig.maxHeaderListSize()).hasValue(1_500_000L);
        assertThat(httpConfig.name()).hasValue("foo");
        assertThat(httpConfig.ping()).hasValue(true);
        assertThat(httpConfig.pingTimeout()).hasValue(Duration.ofSeconds(3));
        assertThat(httpConfig.priorKnowledge()).hasValue(true);
    }

    @Test
    void testToHttp2ClientProtocolConfig() {
        final Duration flowControlBlockTimeout = Duration.ofSeconds(10);
        final int initialWindowSize = 15_000;
        final int maxFrameSize = 32_768;
        final long maxHeaderListSize = 1_500_000;
        final String name = "foo";
        final boolean ping = true;
        final Duration pingTimeout = Duration.ofSeconds(3);
        final boolean priorKnowledge = true;

        final BlockNodeHelidonHttpConfiguration httpConfig = BlockNodeHelidonHttpConfiguration.newBuilder()
                .flowControlBlockTimeout(flowControlBlockTimeout)
                .initialWindowSize(initialWindowSize)
                .maxFrameSize(maxFrameSize)
                .maxHeaderListSize(maxHeaderListSize)
                .name(name)
                .ping(ping)
                .pingTimeout(pingTimeout)
                .priorKnowledge(priorKnowledge)
                .build();
        final Http2ClientProtocolConfig protocolConfig = httpConfig.toHttp2ClientProtocolConfig();

        assertThat(protocolConfig.flowControlBlockTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(protocolConfig.initialWindowSize()).isEqualTo(15_000);
        assertThat(protocolConfig.maxFrameSize()).isEqualTo(32_768);
        assertThat(protocolConfig.maxHeaderListSize()).isEqualTo(1_500_000L);
        assertThat(protocolConfig.name()).isEqualTo("foo");
        assertThat(protocolConfig.ping()).isTrue();
        assertThat(protocolConfig.pingTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(protocolConfig.priorKnowledge()).isTrue();
    }
}
