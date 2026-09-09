// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeTlsConfig;
import com.hedera.node.internal.network.HelidonGrpcConfig;
import com.hedera.node.internal.network.HelidonHttpConfig;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
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
                .hasMessage("Message size hard limit (100) must be greater than or equal to soft limit size (1000)");
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
        assertThatThrownBy(() -> BlockNodeConfiguration.from(null, 6_000_000L))
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

        final BlockNodeConfiguration config = BlockNodeConfiguration.from(cfg, 36L * 1024 * 1024);
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

        // No TLS blocks were declared, so both endpoints stay plaintext
        assertThat(config.streamingTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
        assertThat(config.serviceTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
    }

    @Test
    void testTlsDefaultsToDisabled() {
        final BlockNodeConfiguration config = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(8080)
                .servicePort(8081)
                .priority(0)
                .messageSizeSoftLimitBytes(1_000)
                .messageSizeHardLimitBytes(2_000)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT)
                .build();

        assertThat(config.streamingTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
        assertThat(config.serviceTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
    }

    @Test
    void testFromBlockNodeConfig_tlsOnPublishApiOnly() {
        final BlockNodeConfig cfg = BlockNodeConfig.newBuilder()
                .address("localhost")
                .streamingPort(8443)
                .servicePort(8080)
                .priority(0)
                .streamingTls(BlockNodeTlsConfig.newBuilder()
                        .enabled(true)
                        .certificateSha384("a".repeat(96))
                        .build())
                .build();

        final BlockNodeConfiguration config = BlockNodeConfiguration.from(cfg, 36L * 1024 * 1024);

        assertThat(config.streamingTls().enabled()).isTrue();
        assertThat(config.streamingTls().certificateSha384()).hasSize(48);
        assertThat(config.serviceTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
    }

    @Test
    void testFromBlockNodeConfig_tlsOnAllApis() {
        final BlockNodeTlsConfig tls =
                BlockNodeTlsConfig.newBuilder().enabled(true).build();
        final BlockNodeConfig cfg = BlockNodeConfig.newBuilder()
                .address("localhost")
                .streamingPort(8443)
                .servicePort(8444)
                .priority(0)
                .streamingTls(tls)
                .serviceTls(tls)
                .build();

        final BlockNodeConfiguration config = BlockNodeConfiguration.from(cfg, 36L * 1024 * 1024);

        assertThat(config.streamingTls().enabled()).isTrue();
        assertThat(config.serviceTls().enabled()).isTrue();
    }

    @Test
    void testFromBlockNodeConfig_invalidTlsIsRejected() {
        final BlockNodeConfig cfg = BlockNodeConfig.newBuilder()
                .address("localhost")
                .streamingPort(8443)
                .priority(0)
                .streamingTls(BlockNodeTlsConfig.newBuilder()
                        .enabled(false)
                        .certificateSha384("a".repeat(96))
                        .build())
                .build();

        assertThatThrownBy(() -> BlockNodeConfiguration.from(cfg, 36L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be specified for an endpoint that does not use TLS");
    }

    @Test
    void testTlsParticipatesInEqualityAndToString() {
        final BlockNodeConfig.Builder base = BlockNodeConfig.newBuilder()
                .address("localhost")
                .streamingPort(8443)
                .servicePort(8444)
                .priority(0);
        final BlockNodeConfiguration plaintext = BlockNodeConfiguration.from(base.build(), 36L * 1024 * 1024);
        final BlockNodeConfiguration secured = BlockNodeConfiguration.from(
                base.streamingTls(BlockNodeTlsConfig.newBuilder().enabled(true).build())
                        .build(),
                36L * 1024 * 1024);

        assertThat(secured).isNotEqualTo(plaintext);
        assertThat(secured.hashCode()).isNotEqualTo(plaintext.hashCode());
        assertThat(secured.toString()).contains("streamingTls=", "serviceTls=");
    }

    /**
     * The shared-endpoint matrix: how the two TLS declarations are reconciled when the service API does, or does not,
     * have an endpoint of its own. On one endpoint the more secure declaration wins and two different TLS
     * configurations are a contradiction.
     */
    @Nested
    class SharedEndpointTls {
        private static final String PIN_A = "a".repeat(96);
        private static final String PIN_B = "b".repeat(96);

        private static BlockNodeConfig.Builder sharedEndpoint() {
            // servicePort omitted -> defaults to streamingPort -> one endpoint for both APIs
            return BlockNodeConfig.newBuilder()
                    .address("localhost")
                    .streamingPort(8443)
                    .priority(0);
        }

        private static BlockNodeTlsConfig tls(final boolean enabled, final String pin) {
            return BlockNodeTlsConfig.newBuilder()
                    .enabled(enabled)
                    .certificateSha384(pin)
                    .build();
        }

        private static BlockNodeConfiguration from(final BlockNodeConfig cfg) {
            return BlockNodeConfiguration.from(cfg, 36L * 1024 * 1024);
        }

        @Test
        void testNeitherDeclaresTls_plaintextForBoth() {
            final BlockNodeConfiguration config = from(sharedEndpoint().build());

            assertThat(config.servicePort()).isEqualTo(config.streamingPort());
            assertThat(config.streamingTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
            assertThat(config.serviceTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.NONE);
            assertThat(config.tlsInheritanceOverrodeExplicitSetting()).isFalse();
        }

        @Test
        void testBothExplicitlyDisabled_plaintextForBoth() {
            final BlockNodeConfiguration config = from(sharedEndpoint()
                    .streamingTls(tls(false, null))
                    .serviceTls(tls(false, null))
                    .build());

            assertThat(config.streamingTls().enabled()).isFalse();
            assertThat(config.serviceTls().enabled()).isFalse();
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.NONE);
        }

        @Test
        void testOnlyStreamingDeclaresTls_serviceTakesIt() {
            final BlockNodeConfiguration config =
                    from(sharedEndpoint().streamingTls(tls(true, PIN_A)).build());

            assertThat(config.serviceTls()).isEqualTo(config.streamingTls());
            assertThat(config.serviceTls().enabled()).isTrue();
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.SERVICE_FROM_STREAMING);
            assertThat(config.tlsInheritanceOverrodeExplicitSetting()).isFalse();
        }

        @Test
        void testOnlyServiceDeclaresTls_streamingTakesIt() {
            final BlockNodeConfiguration config =
                    from(sharedEndpoint().serviceTls(tls(true, PIN_A)).build());

            assertThat(config.streamingTls()).isEqualTo(config.serviceTls());
            assertThat(config.streamingTls().enabled()).isTrue();
            assertThat(config.streamingTls().certificateSha384()).hasSize(48);
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.STREAMING_FROM_SERVICE);
            assertThat(config.tlsInheritanceOverrodeExplicitSetting()).isFalse();
        }

        @Test
        void testStreamingTlsOverridesExplicitlyDisabledServiceTls() {
            final BlockNodeConfiguration config = from(sharedEndpoint()
                    .streamingTls(tls(true, PIN_A))
                    .serviceTls(tls(false, null))
                    .build());

            assertThat(config.serviceTls()).isEqualTo(config.streamingTls());
            assertThat(config.serviceTls().enabled()).isTrue();
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.SERVICE_FROM_STREAMING);
            assertThat(config.tlsInheritanceOverrodeExplicitSetting()).isTrue();
        }

        @Test
        void testServiceTlsOverridesExplicitlyDisabledStreamingTls() {
            final BlockNodeConfiguration config = from(sharedEndpoint()
                    .streamingTls(tls(false, null))
                    .serviceTls(tls(true, null))
                    .build());

            assertThat(config.streamingTls()).isEqualTo(config.serviceTls());
            assertThat(config.streamingTls().enabled()).isTrue();
            assertThat(config.streamingTls().certificateSha384()).isNull();
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.STREAMING_FROM_SERVICE);
            assertThat(config.tlsInheritanceOverrodeExplicitSetting()).isTrue();
        }

        @Test
        void testBothDeclareIdenticalTls_accepted() {
            final BlockNodeConfiguration config = from(sharedEndpoint()
                    .streamingTls(tls(true, PIN_A))
                    .serviceTls(tls(true, PIN_A))
                    .build());

            assertThat(config.streamingTls().enabled()).isTrue();
            assertThat(config.serviceTls().enabled()).isTrue();
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.NONE);
            assertThat(config.tlsInheritanceOverrodeExplicitSetting()).isFalse();
        }

        @Test
        void testBothDeclareTlsWithDifferentFingerprints_rejected() {
            final BlockNodeConfig cfg = sharedEndpoint()
                    .streamingTls(tls(true, PIN_A))
                    .serviceTls(tls(true, PIN_B))
                    .build();

            assertThatThrownBy(() -> from(cfg))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("share endpoint localhost:8443")
                    .hasMessageContaining("both require TLS, but with different settings");
        }

        @Test
        void testBothDeclareTlsPinnedVersusUnpinned_rejected() {
            final BlockNodeConfig cfg = sharedEndpoint()
                    .streamingTls(tls(true, PIN_A))
                    .serviceTls(tls(true, null))
                    .build();

            assertThatThrownBy(() -> from(cfg))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both require TLS, but with different settings");
        }

        @Test
        void testExplicitServicePortEqualToStreamingPortIsASharedEndpoint() {
            final BlockNodeConfiguration config = from(sharedEndpoint()
                    .servicePort(8443)
                    .streamingTls(tls(true, null))
                    .build());

            assertThat(config.serviceTls()).isEqualTo(config.streamingTls());
            assertThat(config.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.SERVICE_FROM_STREAMING);
        }

        @Test
        void testSentinelServicePortIsASharedEndpoint() {
            // "servicePort": -1 is the sentinel some deployments emit for "same as the streaming port"
            final BlockNodeConfiguration config = from(sharedEndpoint()
                    .servicePort(-1)
                    .streamingTls(tls(true, null))
                    .build());

            assertThat(config.servicePort()).isEqualTo(8443);
            assertThat(config.serviceTls().enabled()).isTrue();
        }

        @Test
        void testSeparatePortsAreIndependent() {
            // acceptance criterion 1: TLS on the publish API only, which is only expressible with distinct ports
            final BlockNodeConfiguration publishOnly = from(sharedEndpoint()
                    .servicePort(8080)
                    .streamingTls(tls(true, null))
                    .build());
            assertThat(publishOnly.streamingTls().enabled()).isTrue();
            assertThat(publishOnly.serviceTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
            assertThat(publishOnly.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.NONE);

            // and the mirror image: nothing flows from service to streaming across distinct ports either
            final BlockNodeConfiguration serviceOnly = from(sharedEndpoint()
                    .servicePort(8080)
                    .serviceTls(tls(true, null))
                    .build());
            assertThat(serviceOnly.streamingTls()).isSameAs(BlockNodeTlsConfiguration.DISABLED);
            assertThat(serviceOnly.serviceTls().enabled()).isTrue();
            assertThat(serviceOnly.tlsInheritance()).isEqualTo(BlockNodeConfiguration.TlsInheritance.NONE);

            // and differing declarations on distinct ports are not a contradiction
            final BlockNodeConfiguration differing = from(sharedEndpoint()
                    .servicePort(8080)
                    .streamingTls(tls(true, PIN_A))
                    .serviceTls(tls(true, PIN_B))
                    .build());
            assertThat(differing.streamingTls()).isNotEqualTo(differing.serviceTls());
        }
    }
}
