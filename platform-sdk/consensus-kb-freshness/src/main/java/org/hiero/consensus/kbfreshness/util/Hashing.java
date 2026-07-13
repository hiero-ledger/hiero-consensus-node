// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic content hashing for finding identity. */
public final class Hashing {

    /** NUL (U+0000) — a field separator that cannot appear in any anchor target or key. */
    private static final char SEP = (char) 0;

    /** Prevents instantiation of this utility class. */
    private Hashing() {}

    /**
     * Returns the first 16 hex characters of the SHA-256 of the SEP-joined parts. Joining on a
     * character that cannot occur in the inputs keeps the boundary unambiguous, so distinct part
     * tuples cannot collide by concatenation.
     *
     * @param parts the parts to join and hash; {@code null} elements are treated as empty strings.
     * @return the first 16 hex characters of the SHA-256 digest of the joined parts.
     */
    public static String id(final String... parts) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(parts[i] == null ? "" : parts[i]);
        }
        return sha256Hex(sb.toString()).substring(0, 16);
    }

    /**
     * Computes the full lower-case hex SHA-256 of the given string.
     *
     * @param s the string to hash, encoded as UTF-8.
     * @return the 64-character lower-case hex digest.
     */
    private static String sha256Hex(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
