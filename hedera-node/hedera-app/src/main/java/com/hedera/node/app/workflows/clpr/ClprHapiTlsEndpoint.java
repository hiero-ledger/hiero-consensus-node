// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.NettyConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This node's HAPI TLS listener as advertised in its {@code ClprEndpoint}: the HAPI TLS port
 * ({@link GrpcConfig#tlsPort()}) and the DER encoding of the certificate that listener presents
 * ({@link NettyConfig#tlsCrtPath()}).
 *
 * <p>CLPR {@code sync} is served on the HAPI ports. A non-empty {@link #certificateDer()} is what tells peers to dial
 * this node's HAPI TLS port over TLS; peers do not verify the certificate itself. When the HAPI TLS certificate or
 * key file is missing — the same condition under which {@code NettyGrpcServerManager} starts without its TLS
 * listener — or the certificate cannot be parsed, {@link #certificateDer()} is empty, and this node advertises its
 * plain HAPI port instead. That is the expected behavior for local development and most test networks.
 *
 * <p>Both values are node-local and read once at startup, like the listeners they describe.
 */
@Singleton
public final class ClprHapiTlsEndpoint {

    private static final Logger logger = LogManager.getLogger(ClprHapiTlsEndpoint.class);

    private final int port;
    private final Bytes certificateDer;

    /**
     * Reads this node's HAPI TLS port and loads its HAPI TLS certificate, if CLPR is enabled.
     *
     * @param configProvider source of the CLPR, gRPC and Netty configuration
     */
    @Inject
    public ClprHapiTlsEndpoint(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);
        final var configuration = configProvider.getConfiguration();
        this.port = configuration.getConfigData(GrpcConfig.class).tlsPort();
        if (!configuration.getConfigData(ClprConfig.class).enabled()) {
            this.certificateDer = Bytes.EMPTY;
            return;
        }
        final var nettyConfig = configuration.getConfigData(NettyConfig.class);
        this.certificateDer = loadCertificateDer(nettyConfig.tlsCrtPath(), nettyConfig.tlsKeyPath());
    }

    /**
     * Returns the HAPI TLS port peers dial when {@link #certificateDer()} is non-empty.
     *
     * @return the HAPI TLS port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the DER-encoded HAPI TLS certificate, or {@link Bytes#EMPTY} when this node serves CLPR {@code sync}
     * in plaintext only.
     *
     * @return the DER-encoded HAPI TLS certificate, possibly empty
     */
    @NonNull
    public Bytes certificateDer() {
        return certificateDer;
    }

    private static Bytes loadCertificateDer(@NonNull final String crtPath, @NonNull final String keyPath) {
        if (!Files.exists(Path.of(crtPath)) || !Files.exists(Path.of(keyPath))) {
            logger.warn(
                    "HAPI TLS cert/key not found ({} / {}) — CLPR sync will be advertised on the plain HAPI port",
                    crtPath,
                    keyPath);
            return Bytes.EMPTY;
        }
        try (var in = Files.newInputStream(Path.of(crtPath))) {
            final var certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            logger.info("CLPR sync will be advertised on the HAPI TLS port with the certificate from {}", crtPath);
            return Bytes.wrap(certificate.getEncoded());
        } catch (final CertificateException | IOException e) {
            logger.warn(
                    "Could not load HAPI TLS cert {} — CLPR sync will be advertised on the plain HAPI port",
                    crtPath,
                    e);
            return Bytes.EMPTY;
        }
    }
}
