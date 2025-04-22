// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

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
}
