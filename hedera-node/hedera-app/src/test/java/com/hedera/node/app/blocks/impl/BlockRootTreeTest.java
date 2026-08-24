// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.NUM_SIBLINGS_PER_BLOCK;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.ASSIGNED_SLOT_COUNT;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.EMPTY_SUBTREE;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.SLOT_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the block-stream-facing facade: that it pads the reserved branches and otherwise defers to
 * {@link BlockRootTreeHasher}, whose own contract is covered by {@code BlockRootTreeHasherTest}.
 */
class BlockRootTreeTest {
    private static final Timestamp A_TIMESTAMP = new Timestamp(1_700_000_000L, 123_456_789);

    @Test
    @DisplayName("the assigned branches are padded with empty reserved branches")
    void assignedBranchesArePaddedWithEmptyReservedBranches() {
        final var assigned = randomSlots(ASSIGNED_SLOT_COUNT);

        final var allSlots = new Bytes[SLOT_COUNT];
        System.arraycopy(assigned, 0, allSlots, 0, ASSIGNED_SLOT_COUNT);
        Arrays.fill(allSlots, ASSIGNED_SLOT_COUNT, SLOT_COUNT, EMPTY_SUBTREE);

        final var timestampLeaf = BlockRootTree.hashTimestampLeaf(A_TIMESTAMP);
        assertThat(BlockRootTree.computeBlockRootHash(timestampLeaf, assigned))
                .isEqualTo(StreamingBlockRootTreeHasher.INSTANCE.computeBlockRootHash(timestampLeaf, allSlots));
    }

    @Test
    @DisplayName("the timestamp overload matches hashing the timestamp leaf first")
    void timestampOverloadMatchesTheHashedLeaf() {
        final var assigned = randomSlots(ASSIGNED_SLOT_COUNT);
        assertThat(BlockRootTree.computeBlockRootHash(A_TIMESTAMP, assigned))
                .isEqualTo(BlockRootTree.computeBlockRootHash(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), assigned));
    }

    @Test
    @DisplayName("the timestamp leaf is the protobuf encoding hashed as a leaf")
    void timestampLeafIsTheHashedProtobufEncoding() {
        assertThat(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP))
                .isEqualTo(BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(A_TIMESTAMP)));
    }

    @Test
    @DisplayName("a block carries NUM_SIBLINGS_PER_BLOCK siblings")
    void siblingCountMatchesTheProofConstant() {
        assertThat(BlockRootTree.computeRootAndSiblings(
                                BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), randomSlots(ASSIGNED_SLOT_COUNT))
                        .siblingHashes())
                .hasSize(NUM_SIBLINGS_PER_BLOCK);
        assertThat(BlockRootTree.siblingCount()).isEqualTo(NUM_SIBLINGS_PER_BLOCK);
    }

    @Test
    @DisplayName("emptyAssignedSlots yields the assigned branch count, all empty")
    void emptyAssignedSlotsYieldsEmptyAssignedSlots() {
        assertThat(BlockRootTree.emptyAssignedSlots())
                .hasSize(ASSIGNED_SLOT_COUNT)
                .containsOnly(EMPTY_SUBTREE);
    }

    @Test
    @DisplayName("callers must supply exactly the assigned branches, not the full tree")
    void wrongAssignedSlotCountThrows() {
        final var timestampLeaf = BlockRootTree.hashTimestampLeaf(A_TIMESTAMP);
        final var allSixteen = randomSlots(SLOT_COUNT);
        assertThatThrownBy(() -> BlockRootTree.computeBlockRootHash(timestampLeaf, allSixteen))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    private static final SplittableRandom RANDOM = new SplittableRandom(7_654_321L);

    private static Bytes[] randomSlots(final int n) {
        final var slots = new Bytes[n];
        Arrays.setAll(slots, i -> {
            final var bytes = new byte[BlockImplUtils.HASH_SIZE];
            for (int j = 0; j < bytes.length; j++) {
                bytes[j] = (byte) RANDOM.nextInt(256);
            }
            return Bytes.wrap(bytes);
        });
        return slots;
    }
}
