// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.hex;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/**
 * A trust manager that accepts exactly one server certificate, identified by its SHA-384 fingerprint.
 * <p>
 * This is how a consensus node trusts a block node presenting a self-signed certificate: rather than distributing
 * trust material, the operator records the certificate's fingerprint in {@code block-nodes.json}. Because the
 * fingerprint identifies the certificate exactly, neither chain building nor hostname verification adds anything, and
 * both are skipped. The certificate's validity period is still enforced, so an expired certificate is rejected rather
 * than silently trusted.
 * <p>
 * The fingerprint is taken over the certificate's DER encoding, using the same SHA-384 digest the network already
 * uses for {@code grpc_certificate_hash} on a node.
 */
final class PinnedCertificateTrustManager implements X509TrustManager {
    /**
     * This trust manager never accepts a certificate on the basis of its issuer, so it advertises no issuers.
     */
    private static final X509Certificate[] NO_ACCEPTED_ISSUERS = new X509Certificate[0];

    /**
     * SHA-384 fingerprint of the only certificate this trust manager accepts.
     */
    private final byte[] expectedFingerprint;

    /**
     * @param expectedFingerprint the SHA-384 fingerprint of the certificate to accept
     */
    PinnedCertificateTrustManager(@NonNull final byte[] expectedFingerprint) {
        this.expectedFingerprint = requireNonNull(expectedFingerprint, "expectedFingerprint is required")
                .clone();
    }

    @Override
    public void checkClientTrusted(@Nullable final X509Certificate[] chain, @Nullable final String authType)
            throws CertificateException {
        // The consensus node is always the client in a block node connection, so it never verifies a peer as a server.
        throw new CertificateException("Client certificate verification is not supported");
    }

    @Override
    public void checkServerTrusted(@Nullable final X509Certificate[] chain, @Nullable final String authType)
            throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Block node presented an empty certificate chain");
        }

        final X509Certificate certificate = chain[0];
        final byte[] actualFingerprint = sha384(certificate);
        if (!MessageDigest.isEqual(expectedFingerprint, actualFingerprint)) {
            throw new CertificateException("Block node certificate does not match the configured fingerprint; expected "
                    + hex(expectedFingerprint) + " but was " + hex(actualFingerprint));
        }

        certificate.checkValidity();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return NO_ACCEPTED_ISSUERS;
    }

    /**
     * @param certificate the certificate to fingerprint
     * @return the SHA-384 hash of the certificate's DER encoding
     * @throws CertificateException if the certificate cannot be encoded
     */
    private static @NonNull byte[] sha384(@NonNull final X509Certificate certificate) throws CertificateException {
        try {
            return noThrowSha384HashOf(certificate.getEncoded());
        } catch (final CertificateEncodingException e) {
            throw new CertificateException("Failed to encode the block node certificate", e);
        }
    }
}
