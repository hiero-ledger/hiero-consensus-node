// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.hiero.base.crypto.CertificateUtils;

/**
 * Test fixture for CLPR mTLS certificates. Each instance is one self-signed ECDSA P-384 CA
 * ({@code basicConstraints:CA:TRUE}) mirroring what an operator publishes in
 * {@code ClprEndpoint.tls_certificate}. It exposes the CA in the shapes the various CLPR tests need:
 * the in-memory cert/keypair, its DER bytes, the cert + unencrypted PKCS#8 key PEM files
 * {@link ClprCaCertManager} loads, and leaf certs signed by the CA.
 *
 * <p>BouncyCastle is registered on first use because {@link CertificateUtils#generateCertificate} adds
 * no extensions, so the CA cert (which needs {@code basicConstraints}) is built directly with BC.
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
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.issuerDn = "CN=" + cn;
        this.caKeyPair = newP384KeyPair();

        final var now = Instant.now();
        final var principal = new X500Principal(issuerDn);
        final var builder = new JcaX509v3CertificateBuilder(
                principal,
                BigInteger.ONE,
                Date.from(now.minusSeconds(86400)),
                Date.from(now.plusSeconds(86400L * 365)),
                principal,
                caKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        final var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(caKeyPair.getPrivate());
        this.caCert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
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
