// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.hiero.base.utility.CommonUtils.hex;
import static org.hiero.base.utility.CommonUtils.unhex;

import com.hedera.node.internal.network.BlockNodeTlsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.hiero.base.crypto.DigestType;

/**
 * TLS configuration for a single block node API endpoint.
 * <p>
 * Each API the consensus node calls on a block node (the streaming/publish API and the service API) is configured
 * independently, so TLS may be required for one and not the other.
 * <p>
 * When a certificate fingerprint is supplied, the endpoint's certificate is accepted if and only if its SHA-384 hash
 * matches; neither the platform trust store nor hostname verification is consulted. This is what allows operators to
 * front a block node with a self-signed certificate without distributing trust material to every consensus node.
 * Without a fingerprint, the certificate is verified against the platform default trust store with hostname
 * verification, as any HTTPS client would.
 */
public class BlockNodeTlsConfiguration {
    /**
     * Length, in bytes, of a certificate fingerprint. SHA-384 is the digest the network already uses for certificate
     * hashes, so the same algorithm identifies a block node's certificate here.
     */
    private static final int FINGERPRINT_LENGTH_BYTES = DigestType.SHA_384.digestLength();

    /**
     * Configuration used when an endpoint declares no TLS settings: connect using plaintext.
     */
    public static final BlockNodeTlsConfiguration DISABLED = newBuilder().build();

    /**
     * Whether TLS is used when connecting to this endpoint.
     */
    private final boolean enabled;
    /**
     * SHA-384 fingerprint of the certificate this endpoint is expected to present, or null to verify the certificate
     * against the platform default trust store instead.
     */
    private final byte[] certificateSha384;

    private BlockNodeTlsConfiguration(final Builder builder) {
        enabled = builder.enabled;
        certificateSha384 = builder.certificateSha384;

        if (!enabled && certificateSha384 != null) {
            throw new IllegalArgumentException(
                    "A certificate fingerprint must not be specified for an endpoint that does not use TLS");
        }
        if (certificateSha384 != null && certificateSha384.length != FINGERPRINT_LENGTH_BYTES) {
            throw new IllegalArgumentException("Certificate fingerprint must be " + FINGERPRINT_LENGTH_BYTES
                    + " bytes, but was " + certificateSha384.length);
        }
    }

    /**
     * @return true if TLS is used when connecting to this endpoint
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @return a copy of the expected certificate fingerprint, or null if the platform trust store is used instead
     */
    public @Nullable byte[] certificateSha384() {
        return certificateSha384 == null ? null : certificateSha384.clone();
    }

    /**
     * Converts this configuration to the Helidon TLS settings used to build a client for this endpoint.
     * <p>
     * A new instance is produced on every call rather than being cached, so that a rotated certificate is picked up
     * the next time a connection is established.
     *
     * @return the Helidon TLS settings for this endpoint
     */
    public @NonNull Tls toTls() {
        if (!enabled) {
            return Tls.builder().enabled(false).build();
        }
        if (certificateSha384 == null) {
            return Tls.builder().enabled(true).build();
        }
        // The fingerprint identifies the certificate exactly, which subsumes both chain and hostname verification.
        return Tls.builder()
                .enabled(true)
                .sslContext(pinnedSslContext())
                .endpointIdentificationAlgorithm(Tls.ENDPOINT_IDENTIFICATION_NONE)
                .build();
    }

    /**
     * @return an SSL context that trusts exactly one certificate, identified by its SHA-384 fingerprint
     */
    private @NonNull SSLContext pinnedSslContext() {
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {new PinnedCertificateTrustManager(certificateSha384)}, null);
            return sslContext;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Failed to create a certificate-pinned SSL context", e);
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BlockNodeTlsConfiguration that = (BlockNodeTlsConfiguration) o;
        return enabled == that.enabled && Arrays.equals(certificateSha384, that.certificateSha384);
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(enabled) + Arrays.hashCode(certificateSha384);
    }

    @Override
    public String toString() {
        return "BlockNodeTlsConfiguration{" + "enabled="
                + enabled + ", certificateSha384="
                + (certificateSha384 == null ? null : "'" + hex(certificateSha384) + "'") + '}';
    }

    public static @NonNull Builder newBuilder() {
        return new Builder();
    }

    /**
     * Converts a BlockNodeTlsConfig proto to a BlockNodeTlsConfiguration object.
     *
     * @param tlsConfig the original configuration to extract, or null if the endpoint declares no TLS settings
     * @return the extracted configuration
     * @throws IllegalArgumentException if the configuration is not internally consistent
     */
    public static @NonNull BlockNodeTlsConfiguration from(@Nullable final BlockNodeTlsConfig tlsConfig) {
        if (tlsConfig == null) {
            return DISABLED;
        }

        return newBuilder()
                .enabled(tlsConfig.enabled())
                .certificateSha384(tlsConfig.certificateSha384())
                .build();
    }

    /**
     * Parses a hex-encoded certificate fingerprint, tolerating the colon-separated form commonly emitted by
     * certificate tooling (for example {@code openssl x509 -noout -fingerprint -sha384}).
     *
     * @param hexFingerprint the hex-encoded fingerprint
     * @return the decoded fingerprint
     * @throws IllegalArgumentException if the value is not a valid SHA-384 fingerprint
     */
    private static @NonNull byte[] parseHexFingerprint(@NonNull final String hexFingerprint) {
        final String normalized = hexFingerprint.replace(":", "").trim();
        final byte[] fingerprint;
        try {
            fingerprint = unhex(normalized);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Certificate fingerprint '" + hexFingerprint + "' is not valid hexadecimal", e);
        }
        if (fingerprint.length != FINGERPRINT_LENGTH_BYTES) {
            throw new IllegalArgumentException("Certificate fingerprint '" + hexFingerprint + "' decodes to "
                    + fingerprint.length + " bytes, but a SHA-384 fingerprint is " + FINGERPRINT_LENGTH_BYTES
                    + " bytes");
        }
        return fingerprint;
    }

    public static class Builder {
        private boolean enabled;
        private byte[] certificateSha384;

        private Builder() {
            // no-op
        }

        public @NonNull Builder enabled(final boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the expected certificate fingerprint from its hex-encoded form. A null or blank value means the
         * platform default trust store is used instead.
         *
         * @param hexFingerprint the hex-encoded SHA-384 fingerprint, or null
         * @return this builder
         * @throws IllegalArgumentException if the value is not a valid SHA-384 fingerprint
         */
        public @NonNull Builder certificateSha384(@Nullable final String hexFingerprint) {
            this.certificateSha384 =
                    hexFingerprint == null || hexFingerprint.isBlank() ? null : parseHexFingerprint(hexFingerprint);
            return this;
        }

        public BlockNodeTlsConfiguration build() {
            return new BlockNodeTlsConfiguration(this);
        }
    }
}
