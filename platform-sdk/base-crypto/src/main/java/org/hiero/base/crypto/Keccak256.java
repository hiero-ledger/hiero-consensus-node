// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.cryptography.libxkcp.Libxkcp;
import java.lang.foreign.MemorySegment;

/// Utility to compute Keccak-256 hash.
public final class Keccak256 {
    private static final Libxkcp LIBXKCP = Libxkcp.getInstance();

    private Keccak256() {}

    /// Compute Keccak-256 hash for a given MemorySegment.
    public static byte[] keccak256DigestOf(final MemorySegment msgSegment) {
        final byte[] hashInstance = new byte[LIBXKCP.sizeOfKeccakHashInstance];
        final MemorySegment hashInstanceSeg = MemorySegment.ofArray(hashInstance);
        int ret;

        ret = LIBXKCP.keccakHashInitialize(
                hashInstanceSeg,
                Libxkcp.SHA3_256_RATE,
                Libxkcp.SHA3_256_CAPACITY,
                Libxkcp.SHA3_256_HASHBITLEN,
                Libxkcp.SHA3_256_DELIMITED_SUFFIX_ORIGINAL);
        if (ret != Libxkcp.KECCAK_SUCCESS) {
            throw new RuntimeException("keccakHashInitialize returned " + ret);
        }

        // Libxkcp currently requires a non-zero length of data to hash.
        // We'll support this eventually: https://github.com/hiero-ledger/hiero-cryptography/issues/679
        // But we cannot upgrade hiero-cryptography now due to a set of incompatible changes there.
        if (msgSegment.byteSize() > 0) {
            ret = LIBXKCP.keccakHashUpdate(hashInstanceSeg, msgSegment, msgSegment.byteSize() * 8L);
            if (ret != Libxkcp.KECCAK_SUCCESS) {
                throw new RuntimeException("keccakHashUpdate returned " + ret);
            }
        }

        final byte[] hash = new byte[Libxkcp.SHA3_256_HASHVAL_LENGTH_BYTES];
        ret = LIBXKCP.keccakHashFinal(hashInstanceSeg, MemorySegment.ofArray(hash));
        if (ret != Libxkcp.KECCAK_SUCCESS) {
            throw new RuntimeException("keccakHashFinal returned " + ret);
        }
        return hash;
    }
}
