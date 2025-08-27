// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Utility class for cryptographic operations.
 */
public class CryptoUtils {

    private CryptoUtils() {}

    /**
     * Check if a certificate is valid.  A certificate is valid if it is not null, has a public key, and can be encoded.
     *
     * @param certificate the certificate to check
     * @return true if the certificate is valid, false otherwise
     */
    public static boolean checkCertificate(@Nullable final Certificate certificate) {
        if (certificate == null) {
            return false;
        }
        if (certificate.getPublicKey() == null) {
            return false;
        }
        try {
            if (certificate.getEncoded().length == 0) {
                return false;
            }
        } catch (final CertificateEncodingException e) {
            return false;
        }
        return true;
    }

    /**
     * Decode a X509Certificate from a byte array that was previously obtained via X509Certificate.getEncoded().
     *
     * @param encoded a byte array with an encoded representation of a certificate
     * @return the certificate reconstructed from its encoded form
     */
    @NonNull
    public static X509Certificate decodeCertificate(@NonNull final byte[] encoded) {
        try (final InputStream in = new ByteArrayInputStream(encoded)) {
            final CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(in);
        } catch (CertificateException | IOException e) {
            throw new CryptographyException(e);
        }
    }
}
