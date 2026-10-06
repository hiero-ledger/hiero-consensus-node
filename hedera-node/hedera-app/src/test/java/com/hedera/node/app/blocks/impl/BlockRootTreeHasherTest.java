// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.ASSIGNED_SLOT_COUNT;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.EMPTY_SUBTREE;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.SIBLING_COUNT;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.SLOT_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the {@link BlockRootTreeHasher} contract against every implementation, and asserts the
 * implementations agree with each other and with the cross-repo conformance constants.
 *
 * <p>The hex constants below are the values agreed with the Block Node implementation. They are the single
 * check that both repos build the same tree, so they are written out literally here rather than recomputed
 * from the code under test.
 */
class BlockRootTreeHasherTest {
    /** {@code sha384(0x00)} — the hash of an empty branch. */
    private static final Bytes EXPECTED_EMPTY_SUBTREE = Bytes.fromHex(
            "bec021b4f368e3069134e012c2b4307083d3a9bdd206e24e5f0d86e13d6636655933ec2b413465966817a9c208a11717");

    /** The root of the eight reserved branches 9-16, all empty. */
    private static final Bytes EXPECTED_EMPTY_RESERVED_HALF = Bytes.fromHex(
            "cf7e7647f57807006f4f5870d2210b5b4038d000b2bfa711bceeb7f4a327346b50c61fda4e5c68110b03ce708fb91cf8");

    /** The root of all sixteen branches, all empty. */
    private static final Bytes EXPECTED_ALL_EMPTY_ROOT = Bytes.fromHex(
            "5028fe48c7fca408b16bd62b8089c8644be351cbc653e6786136ce144055d18f9495864b270772f664004eed7b97e6b7");

    private static final Timestamp A_TIMESTAMP = new Timestamp(1_700_000_000L, 123_456_789);
    private static final Bytes A_TIMESTAMP_LEAF = BlockRootTree.hashTimestampLeaf(A_TIMESTAMP);

    /** Every implementation of the contract, so each test below runs against all of them. */
    static Stream<Arguments> allImplementations() {
        return Stream.of(
                Arguments.of("streaming", StreamingBlockRootTreeHasher.INSTANCE),
                Arguments.of("cachedReservedHalf", CachedReservedHalfBlockRootTreeHasher.INSTANCE));
    }

    @Nested
    @DisplayName("Cross-repo conformance constants")
    class ConformanceConstants {
        @Test
        @DisplayName("an empty sub-tree hashes to sha384(0x00)")
        void emptySubtreeMatchesSpec() {
            assertThat(EMPTY_SUBTREE).isEqualTo(EXPECTED_EMPTY_SUBTREE);
        }

        @Test
        @DisplayName("the reserved branches 9-16 hash to the spec's value")
        void reservedHalfMatchesSpec() {
            assertThat(CachedReservedHalfBlockRootTreeHasher.EMPTY_RESERVED_HALF)
                    .isEqualTo(EXPECTED_EMPTY_RESERVED_HALF);
            assertThat(StreamingBlockRootTreeHasher.streamedRootOf(emptySlots(SLOT_COUNT / 2)))
                    .isEqualTo(EXPECTED_EMPTY_RESERVED_HALF);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("a tree of sixteen empty branches matches the spec")
        void allEmptyRootMatchesSpec(final String name, final BlockRootTreeHasher hasher) {
            final var expected = BlockImplUtils.hashInternalNode(A_TIMESTAMP_LEAF, EXPECTED_ALL_EMPTY_ROOT);
            assertThat(hasher.computeBlockRootHash(A_TIMESTAMP_LEAF, emptySlots(SLOT_COUNT)))
                    .isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Agreement between implementations")
    class ImplementationAgreement {
        /**
         * Exhaustively covers all 2^8 combinations of populated and empty assigned branches, since the cached
         * reserved half must hold no matter which branches happen to be empty.
         */
        @ParameterizedTest(name = "branch presence bitmask {0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#everySlotPresenceCombination")
        @DisplayName("both implementations agree for every combination of populated and empty branches")
        void implementationsAgreeForEveryPresenceCombination(final int presenceBitmask) {
            final var slots = slotsForPresence(presenceBitmask);

            final var streaming = StreamingBlockRootTreeHasher.INSTANCE.computeRootAndSiblings(A_TIMESTAMP_LEAF, slots);
            final var cached =
                    CachedReservedHalfBlockRootTreeHasher.INSTANCE.computeRootAndSiblings(A_TIMESTAMP_LEAF, slots);

            assertThat(cached.blockRootHash()).isEqualTo(streaming.blockRootHash());
            assertThat(cached.siblingHashes()).isEqualTo(streaming.siblingHashes());
        }

        @Test
        @DisplayName("both implementations agree on a wrapped record block")
        void implementationsAgreeOnAWrappedRecordBlock() {
            final var slots = emptySlots(SLOT_COUNT);
            slots[0] = randomHash();
            slots[1] = randomHash();
            slots[5] = randomHash();

            assertThat(CachedReservedHalfBlockRootTreeHasher.INSTANCE.computeBlockRootHash(A_TIMESTAMP_LEAF, slots))
                    .isEqualTo(StreamingBlockRootTreeHasher.INSTANCE.computeBlockRootHash(A_TIMESTAMP_LEAF, slots));
        }
    }

    @Nested
    @DisplayName("Tree shape")
    class TreeShape {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("every block yields SIBLING_COUNT siblings, whatever is empty")
        void siblingCountIsFixed(final String name, final BlockRootTreeHasher hasher) {
            final var populated = populatedSlots();
            final var allEmpty = emptySlots(SLOT_COUNT);
            // The common case: no trace data
            final var noTraceData = populatedSlots();
            noTraceData[7] = EMPTY_SUBTREE;

            for (final var slots : List.of(populated, allEmpty, noTraceData)) {
                assertThat(hasher.computeRootAndSiblings(A_TIMESTAMP_LEAF, slots)
                                .siblingHashes())
                        .hasSize(SIBLING_COUNT);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("emptying a later branch does not move an earlier branch's path")
        void emptyingASlotDoesNotMoveOthers(final String name, final BlockRootTreeHasher hasher) {
            final var populated = populatedSlots();
            final var noTraceData = populated.clone();
            noTraceData[7] = EMPTY_SUBTREE;

            final var withTrace = hasher.computeRootAndSiblings(A_TIMESTAMP_LEAF, populated);
            final var withoutTrace = hasher.computeRootAndSiblings(A_TIMESTAMP_LEAF, noTraceData);

            // Branch 8 only feeds the third sibling, so the other three are untouched and branch 1 keeps its depth
            assertThat(withoutTrace.siblingHashes()[0]).isEqualTo(withTrace.siblingHashes()[0]);
            assertThat(withoutTrace.siblingHashes()[1]).isEqualTo(withTrace.siblingHashes()[1]);
            assertThat(withoutTrace.siblingHashes()[2]).isNotEqualTo(withTrace.siblingHashes()[2]);
            assertThat(withoutTrace.siblingHashes()[3]).isEqualTo(withTrace.siblingHashes()[3]);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("the siblings climb from branch 1 back to the block root")
        void siblingsReconstructTheRoot(final String name, final BlockRootTreeHasher hasher) {
            final var slots = populatedSlots();
            final var actual = hasher.computeRootAndSiblings(A_TIMESTAMP_LEAF, slots);

            var hash = slots[0];
            for (final var sibling : actual.siblingHashes()) {
                assertThat(sibling.isFirst())
                        .withFailMessage("Every sibling on branch 1's path is a right sibling")
                        .isFalse();
                hash = BlockImplUtils.hashInternalNode(hash, sibling.siblingHash());
            }
            hash = BlockImplUtils.hashInternalNode(A_TIMESTAMP_LEAF, hash);

            assertThat(hash).isEqualTo(actual.blockRootHash());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("the siblings are the sub-tree roots on branch 1's path")
        void siblingsAreTheSubtreeRootsOnSlotZerosPath(final String name, final BlockRootTreeHasher hasher) {
            final var slots = populatedSlots();

            final var expected = List.of(
                    slots[1],
                    StreamingBlockRootTreeHasher.streamedRootOf(Arrays.copyOfRange(slots, 2, 4)),
                    StreamingBlockRootTreeHasher.streamedRootOf(Arrays.copyOfRange(slots, 4, 8)),
                    StreamingBlockRootTreeHasher.streamedRootOf(Arrays.copyOfRange(slots, 8, 16)));

            final var actual = Arrays.stream(hasher.computeRootAndSiblings(A_TIMESTAMP_LEAF, slots)
                            .siblingHashes())
                    .map(MerkleSiblingHash::siblingHash)
                    .toList();

            assertThat(actual).containsExactlyElementsOf(expected);
        }
    }

    @Nested
    @DisplayName("Reserved branches")
    class ReservedSlots {
        @Test
        @DisplayName("the streaming implementation can assign a reserved branch")
        void streamingSupportsAssigningAReservedSlot() {
            final var slots = emptySlots(SLOT_COUNT);
            final var withReservedEmpty =
                    StreamingBlockRootTreeHasher.INSTANCE.computeBlockRootHash(A_TIMESTAMP_LEAF, slots);

            slots[ASSIGNED_SLOT_COUNT] = randomHash();
            assertThat(StreamingBlockRootTreeHasher.INSTANCE.computeBlockRootHash(A_TIMESTAMP_LEAF, slots))
                    .isNotEqualTo(withReservedEmpty);
        }

        @Test
        @DisplayName("the caching implementation refuses a populated reserved branch rather than ignoring it")
        void cachedReservedHalfRejectsAPopulatedReservedSlot() {
            final var slots = emptySlots(SLOT_COUNT);
            slots[ASSIGNED_SLOT_COUNT] = randomHash();

            assertThatThrownBy(() -> CachedReservedHalfBlockRootTreeHasher.INSTANCE.computeBlockRootHash(
                            A_TIMESTAMP_LEAF, slots))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved")
                    .hasMessageContaining("StreamingBlockRootTreeHasher");
        }
    }

    @Nested
    @DisplayName("Input validation")
    class InputValidation {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("the wrong number of branches is rejected")
        void wrongSlotCountThrows(final String name, final BlockRootTreeHasher hasher) {
            final var tooFew = emptySlots(SLOT_COUNT - 1);
            assertThatThrownBy(() -> hasher.computeBlockRootHash(A_TIMESTAMP_LEAF, tooFew))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("16");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("a null branch is rejected rather than silently treated as empty")
        void nullSlotThrows(final String name, final BlockRootTreeHasher hasher) {
            final var withNull = emptySlots(SLOT_COUNT);
            withNull[3] = null;
            assertThatThrownBy(() -> hasher.computeBlockRootHash(A_TIMESTAMP_LEAF, withNull))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Branch 4");
        }
    }

    private static Bytes[] emptySlots(final int n) {
        final var slots = new Bytes[n];
        Arrays.fill(slots, EMPTY_SUBTREE);
        return slots;
    }

    /** All assigned branches populated, reserved branches empty — what a busy block looks like. */
    private static Bytes[] populatedSlots() {
        final var slots = emptySlots(SLOT_COUNT);
        Arrays.setAll(slots, i -> i < ASSIGNED_SLOT_COUNT ? randomHash() : EMPTY_SUBTREE);
        return slots;
    }

    /** Every combination of populated and empty assigned branches, as a bitmask where bit i means branch i+1 is set. */
    static IntStream everySlotPresenceCombination() {
        return IntStream.range(0, 1 << ASSIGNED_SLOT_COUNT);
    }

    private static Bytes[] slotsForPresence(final int presenceBitmask) {
        final var slots = emptySlots(SLOT_COUNT);
        Arrays.setAll(
                slots,
                i -> i < ASSIGNED_SLOT_COUNT && (presenceBitmask & (1 << i)) != 0 ? randomHash() : EMPTY_SUBTREE);
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
}
