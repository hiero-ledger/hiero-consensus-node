// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalStreamingHasherTest {
    @Test
    void emptyTreeHasNoRootHash() {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);

        assertTrue(hasher.isEmpty());
        assertEquals(0, hasher.leafCount());
        // A tree with no leaves has no root hash. Callers rely on this null to omit the subtree from the block
        // merkle tree, and to persist it as an absent (zero-length) field.
        assertNull(hasher.computeRootHash());
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
        assertEquals(Bytes.wrap(expectedLeafHash), hasher.computeRootHash());
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
        assertEquals(Bytes.wrap(expectedRoot), hasher.computeRootHash());
    }

    @Test
    void aZeroLengthLeafIsStillDistinguishableFromAnEmptyTree() {
        // Zero-length is not in the codomain of SHA-384, so the absent-subtree encoding can never collide with
        // a real root hash -- not even for the one input that used to be a problem, a single empty leaf.
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        hasher.addLeaf(new byte[0]);

        assertFalse(hasher.isEmpty());
        final var rootHash = hasher.computeRootHash();
        assertEquals(Bytes.wrap(BlockImplUtils.hashLeaf(new byte[0])), rootHash);
        // The distinction the old HASH_OF_ZERO sentinel could not make.
        assertEquals(rootHash, BlockImplUtils.presentSubtreeHash(rootHash));
        assertNull(BlockImplUtils.presentSubtreeHash(Bytes.EMPTY));
    }
}
