// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.NamedParameterSpec;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.PEMEncoder;

/**
 * Test fixture for CLPR mTLS certificates. Each instance is one self-signed ECDSA P-384 CA
 * ({@code basicConstraints:CA:TRUE}) mirroring what an operator publishes in
 * {@code ClprEndpoint.tls_certificate}. It exposes the CA in the shapes the various CLPR tests need:
 * the in-memory cert/keypair, its DER bytes, the cert + unencrypted PKCS#8 key PEM files
 * {@link ClprCaCertManager} loads, and leaf certs signed by the CA.
 */
public final class ClprTestCa {
    private static final String SIGNATURE_ALGORITHM = "SHA384withECDSA";

    private final KeyPair caKeyPair;
    private final X509Certificate caCert;
    private final String issuerDn;

    /** A leaf certificate and its keypair, signed by the owning CA. */
    public record Leaf(X509Certificate cert, KeyPair keyPair) {
        public PrivateKey privateKey() {
            return keyPair.getPrivate();
        }
    }

    /** Generates a self-signed P-384 CA with subject/issuer {@code CN=<cn>}. */
    public ClprTestCa(final String cn) throws Exception {
        this.issuerDn = "CN=" + cn;
        this.caKeyPair = newP384KeyPair();

        // Pass `true` to request a critical BasicConstraints(cA == true) extension:
        this.caCert = CertificateUtils.generateCertificate(
                issuerDn, caKeyPair, issuerDn, caKeyPair, new SecureRandom(), SIGNATURE_ALGORITHM, true);
    }

    /** The CA certificate (the trust anchor peers pin). */
    public X509Certificate caCert() {
        return caCert;
    }

    /** The CA keypair — its private key signs leaf certs; its public key verifies them. */
    public KeyPair caKeyPair() {
        return caKeyPair;
    }

    /** The DER encoding of the CA cert, as advertised in {@code ClprEndpoint.tls_certificate}. */
    public byte[] caCertDer() throws Exception {
        return caCert.getEncoded();
    }

    /**
     * Signs a leaf cert (EC P-384, no {@code basicConstraints} so it is accepted as a leaf) under this
     * CA, returning the cert and its keypair.
     */
    public Leaf signLeaf(final String cn) throws Exception {
        final var leafKeyPair = newP384KeyPair();
        final var cert = CertificateUtils.generateCertificate(
                CertificateUtils.distinguishedName(cn),
                leafKeyPair,
                issuerDn,
                caKeyPair,
                new SecureRandom(),
                SIGNATURE_ALGORITHM);
        return new Leaf(cert, leafKeyPair);
    }

    /** Writes this CA as the cert + unencrypted PKCS#8 key PEMs that {@link ClprCaCertManager} loads. */
    public void writePem(final Path certPath, final Path keyPath) throws Exception {
        Files.writeString(certPath, PEMEncoder.encode(PEMEncoder.CERTIFICATE, caCert.getEncoded()));
        Files.writeString(
                keyPath,
                PEMEncoder.encode(PEMEncoder.PRIVATE_KEY, caKeyPair.getPrivate().getEncoded()));
    }

    private static KeyPair newP384KeyPair() throws Exception {
        final var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"), new SecureRandom());
        return gen.generateKeyPair();
    }

    public static KeyPair newEd25519KeyPair() throws Exception {
        final var gen = KeyPairGenerator.getInstance("EdDSA");
        gen.initialize(new NamedParameterSpec("Ed25519"));
        return gen.generateKeyPair();
    }
}
