// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClprHapiTlsEndpointTest {

    private static final int HAPI_TLS_PORT = 50212;

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("given the HAPI TLS cert and key exist, then the DER-encoded cert and the HAPI TLS port are exposed")
    void givenHapiTlsCertAndKeyExist_thenDerEncodedCertAndHapiTlsPortAreExposed() throws Exception {
        final var tlsCert = new ClprTestTlsCert("test-hapi-tls");
        final var crt = tempDir.resolve("hedera.crt");
        final var key = tempDir.resolve("hedera.key");
        tlsCert.writePem(crt, key);

        final var subject = endpointFor(true, crt, key);

        assertThat(subject.certificateDer()).isEqualTo(Bytes.wrap(tlsCert.der()));
        assertThat(subject.port()).isEqualTo(HAPI_TLS_PORT);
    }

    @Test
    @DisplayName("given CLPR is disabled, then no certificate is loaded")
    void givenClprDisabled_thenNoCertificateIsLoaded() throws Exception {
        final var crt = tempDir.resolve("hedera.crt");
        final var key = tempDir.resolve("hedera.key");
        new ClprTestTlsCert("test-hapi-tls").writePem(crt, key);

        final var subject = endpointFor(false, crt, key);

        assertThat(subject.certificateDer()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("given the HAPI TLS key is missing, then no certificate is exposed")
    void givenHapiTlsKeyMissing_thenNoCertificateIsExposed() throws Exception {
        final var crt = tempDir.resolve("hedera.crt");
        new ClprTestTlsCert("test-hapi-tls").writePem(crt, tempDir.resolve("unused.key"));

        final var subject = endpointFor(true, crt, tempDir.resolve("missing.key"));

        assertThat(subject.certificateDer()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("given the HAPI TLS cert is missing, then no certificate is exposed")
    void givenHapiTlsCertMissing_thenNoCertificateIsExposed() throws Exception {
        final var key = tempDir.resolve("hedera.key");
        new ClprTestTlsCert("test-hapi-tls").writePem(tempDir.resolve("unused.crt"), key);

        final var subject = endpointFor(true, tempDir.resolve("missing.crt"), key);

        assertThat(subject.certificateDer()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("given the HAPI TLS cert is unparseable, then no certificate is exposed")
    void givenHapiTlsCertUnparseable_thenNoCertificateIsExposed() throws Exception {
        final var crt = tempDir.resolve("hedera.crt");
        final var key = tempDir.resolve("hedera.key");
        Files.writeString(crt, "not a certificate");
        Files.writeString(key, "not a key");

        final var subject = endpointFor(true, crt, key);

        assertThat(subject.certificateDer()).isEqualTo(Bytes.EMPTY);
    }

    private static ClprHapiTlsEndpoint endpointFor(final boolean clprEnabled, final Path crt, final Path key) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .withValue("grpc.tlsPort", HAPI_TLS_PORT)
                .withValue("netty.tlsCrt.path", crt.toString())
                .withValue("netty.tlsKey.path", key.toString())
                .getOrCreateConfig();
        return new ClprHapiTlsEndpoint(() -> new VersionedConfigImpl(config, 1));
    }
}
