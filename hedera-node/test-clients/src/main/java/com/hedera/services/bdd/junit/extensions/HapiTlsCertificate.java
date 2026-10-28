// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.crypto.PEMEncoder;

/**
 * Test-harness fixture for a node's HAPI TLS listener: a self-signed ECDSA P-384 certificate and key,
 * written as the PEM files a node loads from {@code netty.tlsCrt.path} / {@code netty.tlsKey.path}. A node
 * that finds both files starts its HAPI TLS listener and advertises it, with this certificate, as its CLPR
 * sync endpoint. Peers do not verify the certificate, so it needs no subject alternative names.
 */
final class HapiTlsCertificate {
    private static final String SIGNATURE_ALGORITHM = "SHA384withECDSA";

    private final KeyPair keyPair;
    private final X509Certificate certificate;

    /** Generates a self-signed P-384 certificate with subject/issuer {@code CN=<cn>}. */
    HapiTlsCertificate(final String cn) throws GeneralSecurityException, KeyGeneratingException {
        this.keyPair = newP384KeyPair();
        final var dn = CertificateUtils.distinguishedName(cn);
        this.certificate = CertificateUtils.generateCertificate(
                dn, keyPair, dn, keyPair, SecureRandom.getInstanceStrong(), SIGNATURE_ALGORITHM);
    }

    /** Writes the certificate and its unencrypted PKCS#8 key as the PEM files the HAPI TLS listener loads. */
    void writePem(final Path certPath, final Path keyPath) throws IOException, CertificateEncodingException {
        Files.writeString(certPath, PEMEncoder.encode(PEMEncoder.CERTIFICATE, certificate.getEncoded()));
        Files.writeString(
                keyPath,
                PEMEncoder.encode(PEMEncoder.PRIVATE_KEY, keyPair.getPrivate().getEncoded()));
    }

    private static KeyPair newP384KeyPair() throws GeneralSecurityException {
        final var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"), new SecureRandom());
        return gen.generateKeyPair();
    }
}
