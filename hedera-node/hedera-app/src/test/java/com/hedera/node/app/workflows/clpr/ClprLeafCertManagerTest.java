// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClprLeafCertManager}. The CA is stubbed so the leaf manager can be exercised
 * in isolation: mTLS off, a successful Ed25519 leaf signed by the CA, and the fallback when leaf
 * generation fails.
 */
class ClprLeafCertManagerTest {

    @Test
    @DisplayName("yields no leaf when the CA manager has mTLS disabled")
    void disabledWhenCaDisabled() {
        final var caManager = mock(ClprCaCertManager.class);
        given(caManager.isMtlsEnabled()).willReturn(false);

        final var subject = new ClprLeafCertManager(caManager);

        assertThat(subject.isMtlsEnabled()).isFalse();
        assertThat(subject.leafCredentials()).isNull();
    }

    @Test
    @DisplayName("generates an Ed25519 leaf signed by the CA when mTLS is enabled")
    void generatesLeafWhenEnabled() throws Exception {
        final var ca = new ClprTestCa("clpr-ca");
        final var caManager = mock(ClprCaCertManager.class);
        given(caManager.isMtlsEnabled()).willReturn(true);
        given(caManager.caCert()).willReturn(ca.caCert());
        given(caManager.caKey()).willReturn(ca.caKeyPair().getPrivate());

        final var subject = new ClprLeafCertManager(caManager);

        assertThat(subject.isMtlsEnabled()).isTrue();
        final var creds = subject.leafCredentials();
        assertThat(creds).isNotNull();
        assertThat(creds.privateKey()).isNotNull();
        // The leaf is an Ed25519 key...
        assertThat(creds.certificate().getPublicKey().getAlgorithm()).contains("Ed");
        // ...signed by the CA (its signature verifies under the CA's public key)...
        assertThatCode(() -> creds.certificate().verify(ca.caCert().getPublicKey()))
                .doesNotThrowAnyException();
        // ...and shaped as a leaf, not a CA (basicConstraints absent => -1).
        assertThat(creds.certificate().getBasicConstraints()).isEqualTo(-1);
    }

    @Test
    @DisplayName("fails fast when leaf generation fails despite a configured CA (missing CA key)")
    void failsFastWhenGenerationFails() throws Exception {
        final var ca = new ClprTestCa("clpr-ca");
        final var caManager = mock(ClprCaCertManager.class);
        given(caManager.isMtlsEnabled()).willReturn(true);
        given(caManager.caCert()).willReturn(ca.caCert());
        // A null signing key makes certificate generation throw; mTLS was requested, so this must fail fast
        // rather than silently downgrade to plaintext.
        given(caManager.caKey()).willReturn(null);

        assertThatThrownBy(() -> new ClprLeafCertManager(caManager))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to generate the CLPR mTLS leaf certificate");
    }
}
