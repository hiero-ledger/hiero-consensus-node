// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.internal.network.BlockNodeTlsConfig;
import io.helidon.common.tls.Tls;
import org.junit.jupiter.api.Test;

class BlockNodeTlsConfigurationTest {

    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void testFromNullIsDisabled() {
        assertThat(BlockNodeTlsConfiguration.from(null)).isSameAs(BlockNodeTlsConfiguration.DISABLED);
    }

    @Test
    void testDisabledByDefault() {
        assertThat(BlockNodeTlsConfiguration.DISABLED.enabled()).isFalse();
        assertThat(BlockNodeTlsConfiguration.DISABLED.certificateSha256()).isNull();
        assertThat(BlockNodeTlsConfiguration.DISABLED.toTls().enabled()).isFalse();
    }

    @Test
    void testExplicitlyDisabled() {
        final BlockNodeTlsConfiguration config = BlockNodeTlsConfiguration.from(
                BlockNodeTlsConfig.newBuilder().enabled(false).build());

        assertThat(config.enabled()).isFalse();
        assertThat(config.toTls().enabled()).isFalse();
    }

    @Test
    void testEnabledWithoutFingerprintUsesPlatformTrustStore() {
        final BlockNodeTlsConfiguration config = BlockNodeTlsConfiguration.from(
                BlockNodeTlsConfig.newBuilder().enabled(true).build());

        assertThat(config.enabled()).isTrue();
        assertThat(config.certificateSha256()).isNull();

        final Tls tls = config.toTls();
        assertThat(tls.enabled()).isTrue();
        // Hostname verification is left in place when the platform trust store is used
        assertThat(tls.prototype().endpointIdentificationAlgorithm()).isEqualTo(Tls.ENDPOINT_IDENTIFICATION_HTTPS);
    }

    @Test
    void testEnabledWithFingerprintPinsCertificate() {
        final BlockNodeTlsConfiguration config = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256(FINGERPRINT)
                .build());

        assertThat(config.enabled()).isTrue();
        assertThat(config.certificateSha256()).hasSize(32);

        final Tls tls = config.toTls();
        assertThat(tls.enabled()).isTrue();
        // The pin identifies the certificate exactly, so hostname verification adds nothing and is disabled
        assertThat(tls.prototype().endpointIdentificationAlgorithm()).isEqualTo(Tls.ENDPOINT_IDENTIFICATION_NONE);
    }

    @Test
    void testFingerprintAccessorReturnsCopy() {
        final BlockNodeTlsConfiguration config = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256(FINGERPRINT)
                .build());

        final byte[] first = config.certificateSha256();
        first[0] = (byte) 0xFF;

        assertThat(config.certificateSha256()).isNotEqualTo(first);
    }

    @Test
    void testColonSeparatedFingerprintIsAccepted() {
        final String colonSeparated = String.join(":", "ab".repeat(32).split("(?<=\\G..)"));
        final BlockNodeTlsConfiguration withColons = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256(colonSeparated)
                .build());
        final BlockNodeTlsConfiguration withoutColons = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256("ab".repeat(32))
                .build());

        assertThat(withColons).isEqualTo(withoutColons);
    }

    @Test
    void testUppercaseFingerprintIsAccepted() {
        final BlockNodeTlsConfiguration upper = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256("AB".repeat(32))
                .build());
        final BlockNodeTlsConfiguration lower = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256("ab".repeat(32))
                .build());

        assertThat(upper).isEqualTo(lower);
    }

    @Test
    void testBlankFingerprintIsTreatedAsAbsent() {
        final BlockNodeTlsConfiguration config = BlockNodeTlsConfiguration.from(BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256("   ")
                .build());

        assertThat(config.certificateSha256()).isNull();
    }

    @Test
    void testNonHexFingerprintIsRejected() {
        final BlockNodeTlsConfig proto = BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256("z".repeat(64))
                .build();

        assertThatThrownBy(() -> BlockNodeTlsConfiguration.from(proto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not valid hexadecimal");
    }

    @Test
    void testWrongLengthFingerprintIsRejected() {
        final BlockNodeTlsConfig proto = BlockNodeTlsConfig.newBuilder()
                .enabled(true)
                .certificateSha256("abcd")
                .build();

        assertThatThrownBy(() -> BlockNodeTlsConfiguration.from(proto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decodes to 2 bytes");
    }

    @Test
    void testFingerprintWithoutTlsIsRejected() {
        final BlockNodeTlsConfig proto = BlockNodeTlsConfig.newBuilder()
                .enabled(false)
                .certificateSha256(FINGERPRINT)
                .build();

        assertThatThrownBy(() -> BlockNodeTlsConfiguration.from(proto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be specified for an endpoint that does not use TLS");
    }

    @Test
    void testEqualsAndHashCode() {
        final BlockNodeTlsConfiguration pinned = BlockNodeTlsConfiguration.newBuilder()
                .enabled(true)
                .certificateSha256(FINGERPRINT)
                .build();
        final BlockNodeTlsConfiguration samePin = BlockNodeTlsConfiguration.newBuilder()
                .enabled(true)
                .certificateSha256(FINGERPRINT)
                .build();
        final BlockNodeTlsConfiguration otherPin = BlockNodeTlsConfiguration.newBuilder()
                .enabled(true)
                .certificateSha256("b".repeat(64))
                .build();
        final BlockNodeTlsConfiguration noPin =
                BlockNodeTlsConfiguration.newBuilder().enabled(true).build();

        assertThat(pinned).isEqualTo(samePin).hasSameHashCodeAs(samePin);
        assertThat(pinned).isNotEqualTo(otherPin).isNotEqualTo(noPin).isNotEqualTo(null);
        assertThat(noPin).isNotEqualTo(BlockNodeTlsConfiguration.DISABLED);
    }

    @Test
    void testToString() {
        assertThat(BlockNodeTlsConfiguration.DISABLED.toString())
                .isEqualTo("BlockNodeTlsConfiguration{enabled=false, certificateSha256=null}");
        assertThat(BlockNodeTlsConfiguration.newBuilder()
                        .enabled(true)
                        .certificateSha256(FINGERPRINT)
                        .build()
                        .toString())
                .isEqualTo("BlockNodeTlsConfiguration{enabled=true, certificateSha256='" + FINGERPRINT + "'}");
    }
}
