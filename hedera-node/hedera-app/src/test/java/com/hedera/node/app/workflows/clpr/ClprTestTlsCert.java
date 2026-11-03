// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.crypto.PEMEncoder;

/**
 * Test fixture for a HAPI TLS server certificate: a self-signed ECDSA P-384 certificate and its key, in the shapes the
 * CLPR tests need — the in-memory cert/key, its DER bytes (what a node advertises in
 * {@code ClprEndpoint.tls_certificate}), and the cert + unencrypted PKCS#8 key PEM files the HAPI TLS listener and
 * {@link ClprHapiTlsEndpoint} load.
 */
public final class ClprTestTlsCert {
    private static final String SIGNATURE_ALGORITHM = "SHA384withECDSA";

    private final KeyPair keyPair;
    private final X509Certificate cert;

    /**
     * Generates a self-signed P-384 certificate with subject/issuer {@code CN=<cn>}.
     *
     * @param cn the common name; it does not need to match the host the certificate is served from
     */
    public ClprTestTlsCert(final String cn) throws GeneralSecurityException, KeyGeneratingException {
        final var dn = "CN=" + cn;
        final var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"), new SecureRandom());
        this.keyPair = gen.generateKeyPair();
        this.cert =
                CertificateUtils.generateCertificate(dn, keyPair, dn, keyPair, new SecureRandom(), SIGNATURE_ALGORITHM);
    }

    /** The certificate. */
    public X509Certificate cert() {
        return cert;
    }

    /** The certificate's private key. */
    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    /** The DER encoding of the certificate, as advertised in {@code ClprEndpoint.tls_certificate}. */
    public byte[] der() throws CertificateEncodingException {
        return cert.getEncoded();
    }

    /** Writes the cert + unencrypted PKCS#8 key as the PEM files the HAPI TLS listener loads. */
    public void writePem(final Path certPath, final Path keyPath) throws IOException, CertificateEncodingException {
        Files.writeString(certPath, PEMEncoder.encode(PEMEncoder.CERTIFICATE, cert.getEncoded()));
        Files.writeString(
                keyPath,
                PEMEncoder.encode(PEMEncoder.PRIVATE_KEY, keyPair.getPrivate().getEncoded()));
    }
}
