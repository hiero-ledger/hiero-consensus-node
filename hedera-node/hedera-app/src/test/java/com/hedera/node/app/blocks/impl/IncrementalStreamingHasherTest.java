// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO_BYTES;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalStreamingHasherTest {
    @Test
    void emptyTreeIsEmptyAndRootHashesToHashOfZeroSentinel() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);

        assertTrue(hasher.isEmpty());
        assertEquals(0, hasher.leafCount());
        assertArrayEquals(HASH_OF_ZERO_BYTES, hasher.computeRootHash());
    }

    @Test
    void singleLeafTreeIsNotEmptyAndRootHashIsTheLeafItselfUnwrapped() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        hasher.addLeaf("leaf".getBytes());

        assertFalse(hasher.isEmpty());
        assertEquals(1, hasher.leafCount());
        // The single pending hash is already a properly-prefixed leaf hash, so the root is that leaf hash
        // itself, not re-hashed as an internal node.
        final var expectedLeafHash = BlockImplUtils.hashLeaf("leaf".getBytes());
        assertArrayEquals(expectedLeafHash, hasher.computeRootHash());
    }

    @Test
    void multiLeafTreeIsNotEmpty() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        hasher.addLeaf("first".getBytes());
        hasher.addLeaf("second".getBytes());

        assertFalse(hasher.isEmpty());
        assertEquals(2, hasher.leafCount());

        final var expectedLeaf1 = BlockImplUtils.hashLeaf("first".getBytes());
        final var expectedLeaf2 = BlockImplUtils.hashLeaf("second".getBytes());
        final var expectedRoot = BlockImplUtils.hashInternalNode(expectedLeaf1, expectedLeaf2);
        assertArrayEquals(expectedRoot, hasher.computeRootHash());
    }

    @Test
    void rejectsEmptyLeafDataThatWouldCollideWithTheHashOfZeroSentinel() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);

        // A single zero-length leaf would hash to SHA384(0x00), i.e. exactly the sentinel computeRootHash()
        // returns for a leafless tree, making the two indistinguishable to presentSubtreeHash().
        assertArrayEquals(HASH_OF_ZERO_BYTES, BlockImplUtils.hashLeaf(new byte[0]));

        assertThrows(IllegalArgumentException.class, () -> hasher.addLeaf(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> hasher.addLeaf(null));
        assertTrue(hasher.isEmpty());
    }
}
