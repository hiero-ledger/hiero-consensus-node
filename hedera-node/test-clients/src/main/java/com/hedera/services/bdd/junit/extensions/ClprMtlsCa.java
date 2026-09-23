// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.hiero.base.crypto.CertificateUtils;

/**
 * Test-harness fixture for CLPR mTLS: a self-signed ECDSA P-384 CA mirroring what an operator
 * publishes in {@code ClprEndpoint.tls_certificate}. Each instance holds one CA cert + key and
 * exposes the shapes the multi-network mTLS test needs:
 * <ul>
 *   <li>{@link #caCertDer()} — the DER bytes advertised on-chain in {@code tls_certificate}; and</li>
 *   <li>{@link #writePem(Path, Path)} — the cert + unencrypted PKCS#8 key PEM files that a node's
 *       {@code ClprCaCertManager} loads from {@code clpr.caCrtPath} / {@code clpr.caKeyPath}.</li>
 * </ul>
 */
final class ClprMtlsCa {
    private static final String SIGNATURE_ALGORITHM = "SHA384withECDSA";

    private final KeyPair caKeyPair;
    private final X509Certificate caCert;

    /** Generates a self-signed P-384 CA with subject/issuer {@code CN=<cn>}. */
    ClprMtlsCa(final String cn) throws Exception {
        this.caKeyPair = newP384KeyPair();
        final var dn = CertificateUtils.distinguishedName(cn);
        this.caCert = CertificateUtils.generateCertificate(
                dn, caKeyPair, dn, caKeyPair, SecureRandom.getInstanceStrong(), SIGNATURE_ALGORITHM);
    }

    /** The DER encoding of the CA cert, as advertised in {@code ClprEndpoint.tls_certificate}. */
    byte[] caCertDer() {
        try {
            return caCert.getEncoded();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to encode CLPR mTLS CA cert", e);
        }
    }

    /** Writes this CA as the cert + unencrypted PKCS#8 key PEMs that {@code ClprCaCertManager} loads. */
    void writePem(final Path certPath, final Path keyPath) throws Exception {
        Files.writeString(certPath, pem("CERTIFICATE", caCert.getEncoded()));
        Files.writeString(keyPath, pem("PRIVATE KEY", caKeyPair.getPrivate().getEncoded()));
    }

    private static KeyPair newP384KeyPair() throws Exception {
        final var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"), new SecureRandom());
        return gen.generateKeyPair();
    }

    private static String pem(final String type, final byte[] der) {
        final var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
