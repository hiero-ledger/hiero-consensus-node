// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ClprCaCertManager}: how it decides whether mTLS is enabled based on the
 * configured CA cert/key paths and whether those files load. It never validates that the cert and
 * key correspond — it only requires both to load — so that is not asserted here.
 */
class ClprCaCertManagerTest {

    private static ConfigProvider providerWith(final String caCrtPath, final String caKeyPath) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", true)
                .withValue("clpr.caCrtPath", caCrtPath)
                .withValue("clpr.caKeyPath", caKeyPath)
                .getOrCreateConfig();
        return () -> new VersionedConfigImpl(config, 1);
    }

    @Test
    @DisplayName("does not load configured CA paths when CLPR is disabled")
    void doesNotLoadCaWhenClprDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .withValue("clpr.caCrtPath", "missing-cert.pem")
                .withValue("clpr.caKeyPath", "missing-key.pem")
                .getOrCreateConfig();

        final var subject = new ClprCaCertManager(() -> new VersionedConfigImpl(config, 1));

        assertThat(subject.isMtlsEnabled()).isFalse();
    }

    @Test
    @DisplayName("mTLS is disabled when both CA paths are blank (the default)")
    void disabledWhenPathsBlank() {
        final var subject = new ClprCaCertManager(providerWith("", ""));

        assertThat(subject.isMtlsEnabled()).isFalse();
        assertThat(subject.caCert()).isNull();
        assertThat(subject.caKey()).isNull();
    }

    @Test
    @DisplayName("mTLS is disabled when only the cert path is set")
    void disabledWhenOnlyCertPathSet(@TempDir final Path dir) throws Exception {
        final var crt = dir.resolve("ca.crt");
        new ClprTestCa("ca").writePem(crt, dir.resolve("ca.key"));

        assertThat(new ClprCaCertManager(providerWith(crt.toString(), "")).isMtlsEnabled())
                .isFalse();
    }

    @Test
    @DisplayName("mTLS is disabled when only the key path is set")
    void disabledWhenOnlyKeyPathSet(@TempDir final Path dir) throws Exception {
        final var key = dir.resolve("ca.key");
        new ClprTestCa("ca").writePem(dir.resolve("ca.crt"), key);

        assertThat(new ClprCaCertManager(providerWith("", key.toString())).isMtlsEnabled())
                .isFalse();
    }

    @Test
    @DisplayName("loads the CA cert and key when both valid PEMs are configured")
    void enabledWithValidCertAndKey(@TempDir final Path dir) throws Exception {
        final var ca = new ClprTestCa("clpr-ca");
        final var crt = dir.resolve("ca.crt");
        final var key = dir.resolve("ca.key");
        ca.writePem(crt, key);

        final var subject = new ClprCaCertManager(providerWith(crt.toString(), key.toString()));

        assertThat(subject.isMtlsEnabled()).isTrue();
        assertThat(subject.caCert()).isEqualTo(ca.caCert());
        assertThat(subject.caKey()).isNotNull();
    }

    @Test
    @DisplayName("loads a binary DER (PKCS#8) CA key")
    void loadsBinaryDerPkcs8Key(@TempDir final Path dir) throws Exception {
        final var ca = new ClprTestCa("clpr-ca");
        final var crt = dir.resolve("ca.crt");
        ca.writePem(crt, dir.resolve("throwaway.key")); // valid cert; the key is written as raw DER below
        final var key = dir.resolve("ca.key.der");
        // getEncoded() is the PKCS#8 DER encoding — binary, no PEM markers.
        Files.write(key, ca.caKeyPair().getPrivate().getEncoded());

        final var subject = new ClprCaCertManager(providerWith(crt.toString(), key.toString()));

        assertThat(subject.isMtlsEnabled()).isTrue();
        assertThat(subject.caKey()).isNotNull();
    }

    @Test
    @DisplayName("mTLS is disabled when the cert file does not exist")
    void disabledWhenCertFileMissing(@TempDir final Path dir) throws Exception {
        final var key = dir.resolve("ca.key");
        new ClprTestCa("ca").writePem(dir.resolve("real.crt"), key); // populate a valid key

        final var missingCrt = dir.resolve("does-not-exist.crt");
        assertThatThrownBy(() ->
                        new ClprCaCertManager(providerWith(missingCrt.toString(), key.toString())).isMtlsEnabled())
                .hasMessageContaining("Failed to read CLPR mTLS certificate");
    }

    @Test
    @DisplayName("mTLS is disabled when the cert file is unparseable")
    void throwsRuntimeExceptionWhenCertFileIsInvalid(@TempDir final Path dir) throws Exception {
        final var key = dir.resolve("ca.key");
        new ClprTestCa("ca").writePem(dir.resolve("real.crt"), key); // populate a valid key
        final var crt = dir.resolve("ca.crt");
        Files.writeString(crt, "not a certificate");

        assertThatThrownBy(() -> new ClprCaCertManager(providerWith(crt.toString(), key.toString())).isMtlsEnabled())
                .hasMessageContaining("Failed to load CLPR mTLS CA cert");
    }

    @Test
    @DisplayName("throws when the key PEM is not valid base64")
    void throwsWhenKeyIsNotValidBase64(@TempDir final Path dir) throws Exception {
        final var crt = dir.resolve("ca.crt");
        new ClprTestCa("ca").writePem(crt, dir.resolve("real.key")); // populate a valid cert
        final var key = dir.resolve("ca.key");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\nnot-valid-base64!!!\n-----END PRIVATE KEY-----");

        // BouncyCastle's PEM base64 decode rejects the body -> DecoderException branch.
        assertThatThrownBy(() -> new ClprCaCertManager(providerWith(crt.toString(), key.toString())))
                .hasMessageContaining("Failed to parse CLPR mTLS private key (CA)");
    }

    @Test
    @DisplayName("throws when the key file does not exist")
    void throwsWhenKeyFileMissing(@TempDir final Path dir) throws Exception {
        final var crt = dir.resolve("ca.crt");
        new ClprTestCa("ca").writePem(crt, dir.resolve("real.key")); // populate a valid cert
        final var missingKey = dir.resolve("does-not-exist.key");

        // Files.readString fails -> IOException branch.
        assertThatThrownBy(() -> new ClprCaCertManager(providerWith(crt.toString(), missingKey.toString())))
                .hasMessageContaining("Failed to read CLPR mTLS private key (CA) file");
    }

    @Test
    @DisplayName("throws when the key is valid base64 but not a PKCS#8 EC key")
    void throwsWhenKeyIsValidBase64ButNotAKey(@TempDir final Path dir) throws Exception {
        final var crt = dir.resolve("ca.crt");
        new ClprTestCa("ca").writePem(crt, dir.resolve("real.key")); // populate a valid cert
        final var key = dir.resolve("ca.key");
        final var notAKey = Base64.getEncoder().encodeToString("this is not a pkcs8 key".getBytes());
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + notAKey + "\n-----END PRIVATE KEY-----");

        // Body decodes, but PEMParser cannot read it as a key object -> IOException branch.
        assertThatThrownBy(() -> new ClprCaCertManager(providerWith(crt.toString(), key.toString())))
                .hasMessageContaining("Failed to read CLPR mTLS CA PEM key");
    }
}
