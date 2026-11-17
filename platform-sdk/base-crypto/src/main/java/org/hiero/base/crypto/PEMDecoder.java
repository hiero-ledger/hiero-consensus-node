// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/// A utility to read PEM data.
public final class PEMDecoder {
    private PEMDecoder() {}

    /// Read a PEM-encoded certificate from an input stream.
    /// This method is capable of reading DER-encoded certificates as well
    /// because JDK supports this out of the box.
    public static X509Certificate readCertificate(final InputStream in) throws CertificateException {
        final CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(in);
    }
}
