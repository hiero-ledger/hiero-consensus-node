// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.hiero.base.crypto.DigestType;

/**
 * Utility methods for block implementation.
 */
public class BlockImplUtils {

    /**
     * Prevent instantiation
     */
    private BlockImplUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Appends the given hash to the given hashes. If the number of hashes exceeds the given maximum, the oldest hash
     * is removed.
     * @param hash the hash to append
     * @param hashes the hashes
     * @param maxHashes the maximum number of hashes
     * @return the new hashes
     */
    public static Bytes appendHash(@NonNull final Bytes hash, @NonNull final Bytes hashes, final int maxHashes) {
        final var limit = HASH_SIZE * maxHashes;
        final byte[] bytes = hashes.toByteArray();
        final byte[] newBytes;
        if (bytes.length < limit) {
            newBytes = new byte[bytes.length + HASH_SIZE];
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            hash.getBytes(0, newBytes, newBytes.length - HASH_SIZE, HASH_SIZE);
        } else {
            newBytes = bytes;
            System.arraycopy(newBytes, HASH_SIZE, newBytes, 0, newBytes.length - HASH_SIZE);
            hash.getBytes(0, newBytes, newBytes.length - HASH_SIZE, HASH_SIZE);
        }
        return Bytes.wrap(newBytes);
    }

    /**
     * Hashes the given left and right hashes.
     * @param leftHash the left hash
     * @param rightHash the right hash
     * @return the combined hash
     */
    public static Bytes combine(@NonNull final Bytes leftHash, @NonNull final Bytes rightHash) {
        return Bytes.wrap(combine(leftHash.toByteArray(), rightHash.toByteArray()));
    }

    /**
     * Hashes the given left and right hashes.
     * @param leftHash the left hash
     * @param rightHash the right hash
     * @return the combined hash
     */
    public static byte[] combine(final byte[] leftHash, final byte[] rightHash) {
        try {
            final var digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            digest.update(leftHash);
            digest.update(rightHash);
            return digest.digest();
        } catch (final NoSuchAlgorithmException fatal) {
            throw new IllegalStateException(fatal);
        }
    }
}
