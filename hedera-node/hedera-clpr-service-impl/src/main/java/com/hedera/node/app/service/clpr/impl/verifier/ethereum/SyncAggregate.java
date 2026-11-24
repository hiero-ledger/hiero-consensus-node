// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The SSZ {@code SyncAggregate} container — the committee's vote on the attested header:
 *
 * <ul>
 *   <li>{@code bits} — {@code Bitvector[committee size]} participation bitmap; bit {@code i} (bit
 *       {@code i % 8} of byte {@code i / 8}) set means committee member {@code i} contributed a
 *       signature share.</li>
 *   <li>{@code signature} — the BLS aggregate (G2 point sum) of exactly those members'
 *       signatures over the header's signing root, as a 256-byte EIP-2537 uncompressed G2 point.</li>
 * </ul>
 *
 * <p>The bits are implicitly authenticated by the signature: any flipped bit changes the expected
 * aggregate public key and fails the pairing check.
 */
record SyncAggregate(@NonNull byte[] bits, @NonNull byte[] signature) {

    /**
     * Length of an EIP-2537 uncompressed G2 point: four 48-byte coordinates, each padded to 64 bytes.
     */
    static final int SIGNATURE_LENGTH = 256;

    /** Whether committee member {@code index} signed. */
    boolean hasSigned(final int index) {
        return ((bits[index / Byte.SIZE] >>> (index % Byte.SIZE)) & 1) == 1;
    }

    /** The number of members that signed. */
    int participantCount() {
        int count = 0;
        for (final byte b : bits) {
            count += Integer.bitCount(b & 0xFF);
        }
        return count;
    }
}
