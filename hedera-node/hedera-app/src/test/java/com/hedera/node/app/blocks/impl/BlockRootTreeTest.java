// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.NUM_SIBLINGS_PER_BLOCK;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.ASSIGNED_SLOT_COUNT;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.SLOT_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the block-stream-facing facade: that it pads the reserved branches with the digest type's empty subtree
 * and otherwise defers to {@link BlockRootTreeHasher}, whose own contract is covered by
 * {@code BlockRootTreeHasherTest}.
 */
class BlockRootTreeTest {
    private static final Timestamp A_TIMESTAMP = new Timestamp(1_700_000_000L, 123_456_789);

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("the assigned branches are padded with empty reserved branches")
    void assignedBranchesArePaddedWithEmptyReservedBranches(final DigestType digestType) {
        final var assigned = randomSlots(ASSIGNED_SLOT_COUNT, digestType);

        final var allSlots = new Bytes[SLOT_COUNT];
        System.arraycopy(assigned, 0, allSlots, 0, ASSIGNED_SLOT_COUNT);
        Arrays.fill(allSlots, ASSIGNED_SLOT_COUNT, SLOT_COUNT, BlockRootTreeHasher.emptySubtreeFor(digestType));

        final var timestampLeaf = timestampLeaf(digestType);
        assertThat(BlockRootTree.computeBlockRootHash(digestType, timestampLeaf, assigned))
                .isEqualTo(StreamingBlockRootTreeHasher.of(digestType).computeBlockRootHash(timestampLeaf, allSlots));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("the timestamp overload matches hashing the timestamp leaf first")
    void timestampOverloadMatchesTheHashedLeaf(final DigestType digestType) {
        final var assigned = randomSlots(ASSIGNED_SLOT_COUNT, digestType);
        assertThat(BlockRootTree.computeBlockRootHash(digestType, A_TIMESTAMP, assigned))
                .isEqualTo(BlockRootTree.computeBlockRootHash(digestType, timestampLeaf(digestType), assigned));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("the block root hash has the digest type's length")
    void blockRootHashHasTheDigestTypesLength(final DigestType digestType) {
        final var root = BlockRootTree.computeBlockRootHash(
                digestType, A_TIMESTAMP, randomSlots(ASSIGNED_SLOT_COUNT, digestType));
        assertThat(root.length()).isEqualTo(digestType.digestLength());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("a block carries NUM_SIBLINGS_PER_BLOCK siblings")
    void siblingCountMatchesTheProofConstant(final DigestType digestType) {
        assertThat(BlockRootTree.computeRootAndSiblings(
                                digestType, timestampLeaf(digestType), randomSlots(ASSIGNED_SLOT_COUNT, digestType))
                        .siblingHashes())
                .hasSize(NUM_SIBLINGS_PER_BLOCK);
        assertThat(BlockRootTree.siblingCount()).isEqualTo(NUM_SIBLINGS_PER_BLOCK);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("emptyAssignedSlots yields the assigned branch count, all empty")
    void emptyAssignedSlotsYieldsEmptyAssignedSlots(final DigestType digestType) {
        assertThat(BlockRootTree.emptyAssignedSlots(digestType))
                .hasSize(ASSIGNED_SLOT_COUNT)
                .containsOnly(BlockRootTreeHasher.emptySubtreeFor(digestType));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    @DisplayName("callers must supply exactly the assigned branches, not the full tree")
    void wrongAssignedSlotCountThrows(final DigestType digestType) {
        final var timestampLeaf = timestampLeaf(digestType);
        final var allSixteen = randomSlots(SLOT_COUNT, digestType);
        assertThatThrownBy(() -> BlockRootTree.computeBlockRootHash(digestType, timestampLeaf, allSixteen))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    private static final SplittableRandom RANDOM = new SplittableRandom(7_654_321L);

    private static Bytes timestampLeaf(final DigestType digestType) {
        return BlockImplUtils.hashLeaf(digestType.buildDigest(), Timestamp.PROTOBUF.toBytes(A_TIMESTAMP));
    }

    private static Bytes[] randomSlots(final int n, final DigestType digestType) {
        final var slots = new Bytes[n];
        Arrays.setAll(slots, i -> {
            final var bytes = new byte[digestType.digestLength()];
            for (int j = 0; j < bytes.length; j++) {
                bytes[j] = (byte) RANDOM.nextInt(256);
            }
            return Bytes.wrap(bytes);
        });
        return slots;
    }
}
