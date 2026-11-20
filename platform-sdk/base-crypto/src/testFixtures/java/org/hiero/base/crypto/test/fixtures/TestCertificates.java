// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.crypto.SigningSchema;

/**
 * Utility class for creating X.509 certificates in tests
 */
public final class TestCertificates {

    private TestCertificates() {}

    /**
     * Creates a well-formed certificate for a freshly generated key of the given algorithm, such as {@code "EC"} or
     * {@code "Ed448"}. The certificate is signed with an RSA key, so the certified key itself need not support
     * signing certificates.
     *
     * @param keyAlgorithm the JCA name of the key algorithm
     * @return the certificate
     */
    @NonNull
    public static X509Certificate createCertificate(@NonNull final String keyAlgorithm) {
        try {
            final KeyPair keyPair = KeyPairGenerator.getInstance(keyAlgorithm).generateKeyPair();
            final KeyPairGenerator caKeyPairGenerator = KeyPairGenerator.getInstance("RSA");
            caKeyPairGenerator.initialize(2048);
            final String distinguishedName = CertificateUtils.distinguishedName("test");
            return CertificateUtils.generateCertificate(
                    distinguishedName,
                    keyPair,
                    distinguishedName,
                    caKeyPairGenerator.generateKeyPair(),
                    new SecureRandom(),
                    SigningSchema.RSA.getSigningAlgorithm());
        } catch (final NoSuchAlgorithmException | KeyGeneratingException e) {
            throw new IllegalStateException("could not create a " + keyAlgorithm + " certificate", e);
        }
    }
}
