// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/// A utility to encode DER data in PEM format.
/// Example PEM types: "CERTIFICATE", "PRIVATE KEY", etc.
/// The DER-encoded data could come from e.g. X509Certificate.getEncoded(),
/// or KeyPair.getPrivate().getEncoded() , etc.
public final class PEMEncoder {

    /// PEM type for CERTIFICATE.
    public static final String CERTIFICATE = "CERTIFICATE";

    /// PEM type for PRIVATE KEY.
    public static final String PRIVATE_KEY = "PRIVATE KEY";

    private static final String BEGIN = "-----BEGIN ";
    private static final String END = "\n-----END ";
    private static final String DELIMITER = "-----\n";

    private static final Base64.Encoder MIME_ENCODER = Base64.getMimeEncoder(64, new byte[] {'\n'});

    private PEMEncoder() {}

    /// Encodes a given DER data of a given PEM type as PEM data and appends it to the appendable.
    public static Appendable encode(final Appendable appendable, final String type, final byte[] der)
            throws IOException {
        return appendable
                .append(BEGIN)
                .append(type)
                .append(DELIMITER)
                .append(MIME_ENCODER.encodeToString(der))
                .append(END)
                .append(type)
                .append(DELIMITER);
    }

    /// Encodes a given DER data as a PEM string of a given type.
    public static String encode(final String type, final byte[] der) {
        final StringBuilder sb = new StringBuilder();
        try {
            encode(sb, type, der);
        } catch (final IOException e) {
            // This shouldn't really happen because we don't do I/O above.
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }
}
