// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.NUM_SIBLINGS_PER_BLOCK;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Conformance and shape tests for {@link BlockRootTree}.
 *
 * <p>The hex constants below are the cross-repo conformance values agreed with the Block Node
 * implementation. They are the single check that both repos build the same tree, so they are written out
 * literally here rather than recomputed from the code under test.
 */
class BlockRootTreeTest {
    /** {@code sha384(0x00)} — the hash of an empty sub-tree. */
    private static final Bytes EXPECTED_EMPTY_SUBTREE = Bytes.fromHex(
            "bec021b4f368e3069134e012c2b4307083d3a9bdd206e24e5f0d86e13d6636655933ec2b413465966817a9c208a11717");

    /** The root of the eight reserved slots 8-15, all empty. */
    private static final Bytes EXPECTED_EMPTY_RESERVED_HALF = Bytes.fromHex(
            "cf7e7647f57807006f4f5870d2210b5b4038d000b2bfa711bceeb7f4a327346b50c61fda4e5c68110b03ce708fb91cf8");

    /** The root of all sixteen slots, all empty. */
    private static final Bytes EXPECTED_ALL_EMPTY_ROOT = Bytes.fromHex(
            "5028fe48c7fca408b16bd62b8089c8644be351cbc653e6786136ce144055d18f9495864b270772f664004eed7b97e6b7");

    private static final Timestamp A_TIMESTAMP = new Timestamp(1_700_000_000L, 123_456_789);

    @Nested
    @DisplayName("Cross-repo conformance constants")
    class ConformanceConstants {
        @Test
        @DisplayName("an empty sub-tree hashes to sha384(0x00)")
        void emptySubtreeMatchesSpec() {
            assertThat(BlockRootTree.EMPTY_SUBTREE).isEqualTo(EXPECTED_EMPTY_SUBTREE);
        }

        @Test
        @DisplayName("the constant-folded reserved half matches the spec")
        void reservedHalfMatchesSpec() {
            assertThat(BlockRootTree.EMPTY_RESERVED_HALF).isEqualTo(EXPECTED_EMPTY_RESERVED_HALF);
        }

        @Test
        @DisplayName("a tree of sixteen empty slots matches the spec")
        void allEmptyRootMatchesSpec() {
            assertThat(streamingRootOf(emptySlots(BlockRootTree.SLOT_COUNT))).isEqualTo(EXPECTED_ALL_EMPTY_ROOT);
        }
    }

    @Nested
    @DisplayName("Use of the streaming hasher")
    class StreamingHasherUse {
        @Test
        @DisplayName("constant-folding the reserved half gives the same root as streaming all sixteen slots")
        void constantFoldingIsTransparent() {
            final var assigned = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT);

            final var allSixteen = emptySlots(BlockRootTree.SLOT_COUNT);
            System.arraycopy(assigned, 0, allSixteen, 0, assigned.length);
            final var expected = BlockImplUtils.hashInternalNode(
                    BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), streamingRootOf(allSixteen));

            assertThat(BlockRootTree.computeBlockRootHash(A_TIMESTAMP, assigned))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the siblings are the sub-tree roots the hasher folded on slot 0's path")
        void siblingsAreTheFoldedSubtreeRoots() {
            final var slots = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT);

            final var expected = List.of(
                    slots[1],
                    streamingRootOf(Arrays.copyOfRange(slots, 2, 4)),
                    streamingRootOf(Arrays.copyOfRange(slots, 4, 8)),
                    BlockRootTree.EMPTY_RESERVED_HALF);

            final var actual = Arrays.stream(
                            BlockRootTree.computeRootAndSiblings(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), slots)
                                    .siblingHashes())
                    .map(MerkleSiblingHash::siblingHash)
                    .toList();

            assertThat(actual).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("a hasher resumed from saved state cannot report a first-leaf path")
        void resumedHasherRefusesToReportAPath() {
            final var seeded = new IncrementalStreamingHasher(
                    sha384DigestOrThrow(), List.of(BlockRootTree.EMPTY_SUBTREE.toByteArray()), 1);
            seeded.addNodeByHash(BlockRootTree.EMPTY_SUBTREE.toByteArray());
            assertThatThrownBy(seeded::pathToFirstLeaf).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Tree shape")
    class TreeShape {
        @Test
        @DisplayName("every block yields NUM_SIBLINGS_PER_BLOCK siblings, whatever is empty")
        void siblingCountIsFixed() {
            final var populated = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            final var allEmpty = emptySlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            // The common case: no trace data
            final var noTraceData = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            noTraceData[7] = BlockRootTree.EMPTY_SUBTREE;

            for (final var slots : List.of(populated, allEmpty, noTraceData)) {
                assertThat(BlockRootTree.computeRootAndSiblings(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), slots)
                                .siblingHashes())
                        .hasSize(NUM_SIBLINGS_PER_BLOCK);
            }
        }

        @Test
        @DisplayName("emptying a later slot does not move an earlier slot's path")
        void emptyingASlotDoesNotMoveOthers() {
            final var populated = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            final var noTraceData = populated.clone();
            noTraceData[7] = BlockRootTree.EMPTY_SUBTREE;

            final var withTrace =
                    BlockRootTree.computeRootAndSiblings(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), populated);
            final var withoutTrace =
                    BlockRootTree.computeRootAndSiblings(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), noTraceData);

            // Slot 7 only feeds the third sibling, so the other three are untouched and slot 0 keeps its depth
            assertThat(withoutTrace.siblingHashes()[0]).isEqualTo(withTrace.siblingHashes()[0]);
            assertThat(withoutTrace.siblingHashes()[1]).isEqualTo(withTrace.siblingHashes()[1]);
            assertThat(withoutTrace.siblingHashes()[2]).isNotEqualTo(withTrace.siblingHashes()[2]);
            assertThat(withoutTrace.siblingHashes()[3]).isEqualTo(withTrace.siblingHashes()[3]);
        }

        @Test
        @DisplayName("the siblings climb from slot 0 back to the block root")
        void siblingsReconstructTheRoot() {
            final var slots = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            final var timestampLeaf = BlockRootTree.hashTimestampLeaf(A_TIMESTAMP);
            final var actual = BlockRootTree.computeRootAndSiblings(timestampLeaf, slots);

            var hash = slots[0];
            for (final var sibling : actual.siblingHashes()) {
                assertThat(sibling.isFirst())
                        .withFailMessage("Every sibling on slot 0's path is a right sibling")
                        .isFalse();
                hash = BlockImplUtils.hashInternalNode(hash, sibling.siblingHash());
            }
            hash = BlockImplUtils.hashInternalNode(timestampLeaf, hash);

            assertThat(hash).isEqualTo(actual.blockRootHash());
        }

        @Test
        @DisplayName("the last sibling is the root of the reserved slots")
        void lastSiblingIsTheReservedHalf() {
            final var actual = BlockRootTree.computeRootAndSiblings(
                    BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT));
            assertThat(actual.siblingHashes()[NUM_SIBLINGS_PER_BLOCK - 1].siblingHash())
                    .isEqualTo(BlockRootTree.EMPTY_RESERVED_HALF);
        }

        @Test
        @DisplayName("a wrapped record block is the same tree with only slots 0, 1 and 5 populated")
        void wrappedRecordBlockUsesTheSameTree() {
            final var previousBlockRoot = randomHash();
            final var allPrevBlockRoots = randomHash();
            final var outputItems = randomHash();
            final var timestampLeaf = BlockRootTree.hashTimestampLeaf(A_TIMESTAMP);

            final var slots = emptySlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            slots[0] = previousBlockRoot;
            slots[1] = allPrevBlockRoots;
            slots[5] = outputItems;

            assertThat(BlockRootTree.computeBlockRootHash(timestampLeaf, slots))
                    .isEqualTo(BlockRootTree.computeBlockRootHash(
                            timestampLeaf,
                            previousBlockRoot,
                            allPrevBlockRoots,
                            BlockRootTree.EMPTY_SUBTREE,
                            BlockRootTree.EMPTY_SUBTREE,
                            BlockRootTree.EMPTY_SUBTREE,
                            outputItems,
                            BlockRootTree.EMPTY_SUBTREE,
                            BlockRootTree.EMPTY_SUBTREE));
        }
    }

    @Nested
    @DisplayName("Input validation")
    class InputValidation {
        @Test
        @DisplayName("the wrong number of slots is rejected")
        void wrongSlotCountThrows() {
            final var tooFew = randomSlots(BlockRootTree.ASSIGNED_SLOT_COUNT - 1);
            assertThatThrownBy(() ->
                            BlockRootTree.computeBlockRootHash(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), tooFew))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("8");
        }

        @Test
        @DisplayName("a null slot is rejected rather than silently treated as empty")
        void nullSlotThrows() {
            final var withNull = emptySlots(BlockRootTree.ASSIGNED_SLOT_COUNT);
            withNull[3] = null;
            assertThatThrownBy(() ->
                            BlockRootTree.computeBlockRootHash(BlockRootTree.hashTimestampLeaf(A_TIMESTAMP), withNull))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Slot 3");
        }
    }

    private static Bytes[] emptySlots(final int n) {
        final var slots = new Bytes[n];
        Arrays.fill(slots, BlockRootTree.EMPTY_SUBTREE);
        return slots;
    }

    private static Bytes[] randomSlots(final int n) {
        final var slots = new Bytes[n];
        Arrays.setAll(slots, i -> randomHash());
        return slots;
    }

    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    private static Bytes randomHash() {
        final var bytes = new byte[BlockImplUtils.HASH_SIZE];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) RANDOM.nextInt(256);
        }
        return Bytes.wrap(bytes);
    }

    private static Bytes streamingRootOf(final Bytes[] nodes) {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        for (final var node : nodes) {
            hasher.addNodeByHash(node.toByteArray());
        }
        return Bytes.wrap(hasher.computeRootHash());
    }
}
