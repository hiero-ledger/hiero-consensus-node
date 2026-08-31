// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.CryptoConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PinnedCertificateTrustManagerTest {

    private static X509Certificate certificate;
    private static X509Certificate otherCertificate;
    private static byte[] fingerprint;

    @BeforeAll
    static void beforeAll() throws Exception {
        certificate = generateCertificate("localhost");
        otherCertificate = generateCertificate("impostor");
        fingerprint = sha256(certificate);
    }

    @Test
    void testMatchingCertificateIsAccepted() {
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatCode(() -> trustManager.checkServerTrusted(new X509Certificate[] {certificate}, "RSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void testOnlyTheLeafCertificateIsConsidered() {
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatCode(() ->
                        trustManager.checkServerTrusted(new X509Certificate[] {certificate, otherCertificate}, "RSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void testDifferentCertificateIsRejected() {
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatThrownBy(() -> trustManager.checkServerTrusted(new X509Certificate[] {otherCertificate}, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("does not match the configured fingerprint");
    }

    @Test
    void testNullChainIsRejected() {
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatThrownBy(() -> trustManager.checkServerTrusted(null, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("empty certificate chain");
    }

    @Test
    void testEmptyChainIsRejected() {
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatThrownBy(() -> trustManager.checkServerTrusted(new X509Certificate[0], "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("empty certificate chain");
    }

    @Test
    void testExpiredCertificateIsRejected() throws Exception {
        final X509Certificate expired = mock(X509Certificate.class);
        when(expired.getEncoded()).thenReturn(certificate.getEncoded());
        doThrow(new CertificateExpiredException("expired")).when(expired).checkValidity();

        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatThrownBy(() -> trustManager.checkServerTrusted(new X509Certificate[] {expired}, "RSA"))
                .isInstanceOf(CertificateExpiredException.class);
    }

    @Test
    void testClientVerificationIsUnsupported() {
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(fingerprint);

        assertThatThrownBy(() -> trustManager.checkClientTrusted(new X509Certificate[] {certificate}, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("Client certificate verification is not supported");
    }

    @Test
    void testNoAcceptedIssuers() {
        assertThat(new PinnedCertificateTrustManager(fingerprint).getAcceptedIssuers())
                .isEmpty();
    }

    @Test
    void testFingerprintIsDefensivelyCopied() throws Exception {
        final byte[] mutable = fingerprint.clone();
        final PinnedCertificateTrustManager trustManager = new PinnedCertificateTrustManager(mutable);
        mutable[0] = (byte) ~mutable[0];

        assertThatCode(() -> trustManager.checkServerTrusted(new X509Certificate[] {certificate}, "RSA"))
                .doesNotThrowAnyException();
    }

    private static X509Certificate generateCertificate(final String commonName) throws Exception {
        final KeyPairGenerator keyGen =
                KeyPairGenerator.getInstance(CryptoConstants.SIG_TYPE1, CryptoConstants.SIG_PROVIDER);
        keyGen.initialize(CryptoConstants.SIG_KEY_SIZE_BITS);
        final KeyPair keyPair = keyGen.generateKeyPair();
        final String distinguishedName = CertificateUtils.distinguishedName(commonName);
        return CertificateUtils.generateCertificate(
                distinguishedName, keyPair, distinguishedName, keyPair, new SecureRandom(), CryptoConstants.SIG_TYPE2);
    }

    private static byte[] sha256(final X509Certificate cert) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
    }
}
