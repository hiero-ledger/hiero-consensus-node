// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BlockRootTreeWitnessTest {
    @ParameterizedTest
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_256", "SHA_384"})
    void compactTimestampWitnessReconstructsTheActualBlockRoot(final DigestType digestType) {
        final var slots = BlockRootTree.emptyAssignedSlots(digestType);
        for (int i = 0; i < slots.length; i++) {
            slots[i] = BlockImplUtils.hashLeaf(digestType.buildDigest(), Bytes.wrap("branch " + i));
        }
        final var timestampHash = BlockImplUtils.hashLeaf(
                digestType.buildDigest(), Timestamp.PROTOBUF.toBytes(new Timestamp(1_234_567, 890)));
        final var full = BlockRootTree.computeRootAndSiblings(digestType, timestampHash, slots);

        final var sibling = BlockRootTree.timestampSiblingHash(digestType, slots[0], full.siblingHashes());

        assertEquals(digestType.digestLength(), sibling.length());
        assertEquals(
                full.blockRootHash(),
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), timestampHash, sibling));
    }

    @ParameterizedTest
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_256", "SHA_384"})
    void rejectsWrongShapeAndSiblingOrientation(final DigestType digestType) {
        final var hash = BlockRootTreeHasher.emptySubtreeFor(digestType);
        final var timestampHash = BlockImplUtils.hashLeaf(digestType.buildDigest(), Bytes.wrap("timestamp"));
        final var siblings = BlockRootTree.computeRootAndSiblings(
                        digestType, timestampHash, BlockRootTree.emptyAssignedSlots(digestType))
                .siblingHashes();
        assertThrows(IllegalArgumentException.class, () -> BlockRootTree.timestampSiblingHash(digestType, hash));
        siblings[0] = new MerkleSiblingHash(true, hash);
        assertThrows(
                IllegalArgumentException.class, () -> BlockRootTree.timestampSiblingHash(digestType, hash, siblings));
        siblings[0] = new MerkleSiblingHash(false, Bytes.EMPTY);
        assertThrows(
                IllegalArgumentException.class, () -> BlockRootTree.timestampSiblingHash(digestType, hash, siblings));
    }

    @ParameterizedTest
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_256", "SHA_384"})
    void digestTransitionCanIncludeAPreviousRootOfDifferentLength(final DigestType digestType) {
        final var slots = BlockRootTree.emptyAssignedSlots(digestType);
        slots[0] = Bytes.wrap(new byte[digestType == DigestType.SHA_256 ? 48 : 32]);
        final var timestampHash = BlockImplUtils.hashLeaf(digestType.buildDigest(), Bytes.wrap("timestamp"));
        final var full = BlockRootTree.computeRootAndSiblings(digestType, timestampHash, slots);
        final var sibling = BlockRootTree.timestampSiblingHash(digestType, slots[0], full.siblingHashes());
        assertEquals(
                full.blockRootHash(),
                BlockImplUtils.hashInternalNode(digestType.buildDigest(), timestampHash, sibling));
    }
}
