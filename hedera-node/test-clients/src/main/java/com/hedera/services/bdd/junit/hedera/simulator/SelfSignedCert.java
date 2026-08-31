// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.common.tls.Tls;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.CryptoConstants;

/**
 * A self-signed certificate used by {@link SimulatedBlockNodeServer} to serve an API over TLS.
 * <p>
 * A consensus node trusts this certificate by pinning its SHA-256 fingerprint in {@code block-nodes.json}, which is
 * exactly how an operator would configure a block node fronted by a self-signed certificate. Because the fingerprint
 * identifies the certificate, hostname verification is not performed and the certificate needs no subject alternative
 * names.
 * <p>
 * Generating a 3072-bit RSA key pair is not free, so a single certificate is generated lazily and shared by every
 * simulator in a test run. Nothing in the block node connection path is sensitive to two block nodes presenting the
 * same certificate.
 */
public final class SelfSignedCert {
    private static volatile SelfSignedCert shared;

    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final String sha256Fingerprint;

    private SelfSignedCert() {
        try {
            final KeyPairGenerator keyGen =
                    KeyPairGenerator.getInstance(CryptoConstants.SIG_TYPE1, CryptoConstants.SIG_PROVIDER);
            keyGen.initialize(CryptoConstants.SIG_KEY_SIZE_BITS);
            keyPair = keyGen.generateKeyPair();

            final String distinguishedName = CertificateUtils.distinguishedName("localhost");
            certificate = CertificateUtils.generateCertificate(
                    distinguishedName,
                    keyPair,
                    distinguishedName,
                    keyPair,
                    new SecureRandom(),
                    CryptoConstants.SIG_TYPE2);
            sha256Fingerprint = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate a self-signed certificate for a simulated block", e);
        }
    }

    /**
     * @return the certificate shared by every simulated block node in this test run
     */
    public static @NonNull SelfSignedCert shared() {
        SelfSignedCert result = shared;
        if (result == null) {
            synchronized (SelfSignedCert.class) {
                result = shared;
                if (result == null) {
                    result = new SelfSignedCert();
                    shared = result;
                }
            }
        }
        return result;
    }

    /**
     * @return the hex-encoded SHA-256 fingerprint a consensus node should pin to trust this certificate
     */
    public @NonNull String sha256Fingerprint() {
        return sha256Fingerprint;
    }

    /**
     * @return Helidon TLS settings that serve this certificate
     */
    public @NonNull Tls serverTls() {
        return Tls.builder()
                .enabled(true)
                .privateKey(keyPair.getPrivate())
                .privateKeyCertChain(List.of(certificate))
                .build();
    }
}
