// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.hiero.base.crypto.Cryptography;
import org.junit.jupiter.api.Test;

class MerkleHasherTest {

    private static final int HASH_LENGTH = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();

    @Test
    void internalNodeHashBytesIntoMatchesAllocatingMethod() {
        final MerkleHasher hasher = new MerkleHasher();
        final byte[] left = childHash((byte) 1);
        final byte[] right = childHash((byte) 2);
        final byte[] expected = hasher.internalNodeHashBytes(left, right);
        final byte[] output = new byte[HASH_LENGTH + 2];
        Arrays.fill(output, (byte) -1);

        hasher.internalNodeHashBytesInto(left, right, output, 1);

        assertArrayEquals(expected, Arrays.copyOfRange(output, 1, HASH_LENGTH + 1));
        assertEquals((byte) -1, output[0]);
        assertEquals((byte) -1, output[HASH_LENGTH + 1]);
    }

    @Test
    void internalNodeHashBytesIntoMatchesAllocatingMethodWithoutRightChild() {
        final MerkleHasher hasher = new MerkleHasher();
        final byte[] left = childHash((byte) 3);
        final byte[] expected = hasher.internalNodeHashBytes(left, null);
        final byte[] output = new byte[HASH_LENGTH];

        hasher.internalNodeHashBytesInto(left, null, output, 0);

        assertArrayEquals(expected, output);
    }

    @Test
    void outputMayAliasLeftChild() {
        final MerkleHasher hasher = new MerkleHasher();
        final byte[] left = childHash((byte) 4);
        final byte[] right = childHash((byte) 5);
        final byte[] expected = hasher.internalNodeHashBytes(left, right);

        hasher.internalNodeHashBytesInto(left, right, left, 0);

        assertArrayEquals(expected, left);
    }

    @Test
    void outputMayAliasRightChild() {
        final MerkleHasher hasher = new MerkleHasher();
        final byte[] left = childHash((byte) 6);
        final byte[] right = childHash((byte) 7);
        final byte[] expected = hasher.internalNodeHashBytes(left, right);

        hasher.internalNodeHashBytesInto(left, right, right, 0);

        assertArrayEquals(expected, right);
    }

    private static byte[] childHash(final byte value) {
        final byte[] hash = new byte[HASH_LENGTH];
        Arrays.fill(hash, value);
        return hash;
    }
}
