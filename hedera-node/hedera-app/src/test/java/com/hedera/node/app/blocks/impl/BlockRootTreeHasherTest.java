// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.ASSIGNED_SLOT_COUNT;
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
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the {@link BlockRootTreeHasher} contract against every implementation and every block digest type, and
 * asserts the implementations agree with each other and with the cross-repo conformance constants.
 *
 * <p>The hex constants below are the SHA-384 values agreed with the Block Node implementation. They are the
 * single check that both repos build the same tree, so they are written out literally here rather than
 * recomputed from the code under test.
 */
class BlockRootTreeHasherTest {
    /** {@code sha384(0x00)} — the hash of an empty branch under SHA-384. */
    private static final Bytes EXPECTED_EMPTY_SUBTREE = Bytes.fromHex(
            "bec021b4f368e3069134e012c2b4307083d3a9bdd206e24e5f0d86e13d6636655933ec2b413465966817a9c208a11717");

    /** The root of the eight reserved branches 9-16, all empty, under SHA-384. */
    private static final Bytes EXPECTED_EMPTY_RESERVED_HALF = Bytes.fromHex(
            "cf7e7647f57807006f4f5870d2210b5b4038d000b2bfa711bceeb7f4a327346b50c61fda4e5c68110b03ce708fb91cf8");

    /** The root of all sixteen branches, all empty, under SHA-384. */
    private static final Bytes EXPECTED_ALL_EMPTY_ROOT = Bytes.fromHex(
            "5028fe48c7fca408b16bd62b8089c8644be351cbc653e6786136ce144055d18f9495864b270772f664004eed7b97e6b7");

    private static final Timestamp A_TIMESTAMP = new Timestamp(1_700_000_000L, 123_456_789);

    /** The digest types block hashing can be configured with. */
    private static final List<DigestType> BLOCK_DIGEST_TYPES = List.of(DigestType.SHA_384, DigestType.SHA_256);

    /** Every implementation of the contract for every block digest type, so each test below runs against all. */
    static Stream<Arguments> allImplementations() {
        return BLOCK_DIGEST_TYPES.stream()
                .flatMap(digestType -> Stream.of(
                        Arguments.of("streaming/" + digestType, StreamingBlockRootTreeHasher.of(digestType)),
                        Arguments.of(
                                "cachedReservedHalf/" + digestType,
                                CachedReservedHalfBlockRootTreeHasher.of(digestType))));
    }

    /** Every combination of block digest type and populated/empty assigned branches. */
    static Stream<Arguments> everyDigestTypeAndSlotPresenceCombination() {
        return BLOCK_DIGEST_TYPES.stream().flatMap(digestType -> IntStream.range(0, 1 << ASSIGNED_SLOT_COUNT)
                .mapToObj(mask -> Arguments.of(digestType, mask)));
    }

    @Nested
    @DisplayName("Cross-repo conformance constants")
    class ConformanceConstants {
        @Test
        @DisplayName("an empty sub-tree hashes to sha384(0x00) under SHA-384")
        void emptySubtreeMatchesSpec() {
            assertThat(CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_384)
                            .emptySubtree())
                    .isEqualTo(EXPECTED_EMPTY_SUBTREE);
            assertThat(BlockRootTreeHasher.emptySubtreeFor(DigestType.SHA_384)).isEqualTo(EXPECTED_EMPTY_SUBTREE);
        }

        @Test
        @DisplayName("the reserved branches 9-16 hash to the spec's value under SHA-384")
        void reservedHalfMatchesSpec() {
            assertThat(CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_384)
                            .emptyReservedHalf())
                    .isEqualTo(EXPECTED_EMPTY_RESERVED_HALF);
            assertThat(StreamingBlockRootTreeHasher.streamedRootOf(
                            DigestType.SHA_384::buildDigest, emptySlots(SLOT_COUNT / 2, DigestType.SHA_384)))
                    .isEqualTo(EXPECTED_EMPTY_RESERVED_HALF);
        }

        @Test
        @DisplayName("a tree of sixteen empty branches matches the spec under SHA-384")
        void allEmptyRootMatchesSpec() {
            final var timestampLeaf = timestampLeaf(DigestType.SHA_384);
            final var expected = BlockImplUtils.hashInternalNode(
                    DigestType.SHA_384.buildDigest(), timestampLeaf, EXPECTED_ALL_EMPTY_ROOT);
            for (final BlockRootTreeHasher hasher : List.of(
                    StreamingBlockRootTreeHasher.of(DigestType.SHA_384),
                    CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_384))) {
                assertThat(hasher.computeBlockRootHash(timestampLeaf, emptySlots(SLOT_COUNT, DigestType.SHA_384)))
                        .isEqualTo(expected);
            }
        }
    }

    @Nested
    @DisplayName("Agreement between implementations")
    class ImplementationAgreement {
        /**
         * Exhaustively covers all 2^8 combinations of populated and empty assigned branches for each digest type,
         * since the cached reserved half must hold no matter which branches happen to be empty.
         */
        @ParameterizedTest(name = "{0}, branch presence bitmask {1}")
        @MethodSource(
                "com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#everyDigestTypeAndSlotPresenceCombination")
        @DisplayName("both implementations agree for every combination of populated and empty branches")
        void implementationsAgreeForEveryPresenceCombination(final DigestType digestType, final int presenceBitmask) {
            final var slots = slotsForPresence(presenceBitmask, digestType);
            final var timestampLeaf = timestampLeaf(digestType);

            final var streaming =
                    StreamingBlockRootTreeHasher.of(digestType).computeRootAndSiblings(timestampLeaf, slots);
            final var cached =
                    CachedReservedHalfBlockRootTreeHasher.of(digestType).computeRootAndSiblings(timestampLeaf, slots);

            assertThat(cached.blockRootHash()).isEqualTo(streaming.blockRootHash());
            assertThat(cached.siblingHashes()).isEqualTo(streaming.siblingHashes());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = DigestType.class,
                names = {"SHA_384", "SHA_256"})
        @DisplayName("both implementations agree on a wrapped record block")
        void implementationsAgreeOnAWrappedRecordBlock(final DigestType digestType) {
            final var slots = emptySlots(SLOT_COUNT, digestType);
            slots[0] = randomHash(digestType);
            slots[1] = randomHash(digestType);
            slots[5] = randomHash(digestType);
            final var timestampLeaf = timestampLeaf(digestType);

            assertThat(CachedReservedHalfBlockRootTreeHasher.of(digestType).computeBlockRootHash(timestampLeaf, slots))
                    .isEqualTo(StreamingBlockRootTreeHasher.of(digestType).computeBlockRootHash(timestampLeaf, slots));
        }
    }

    @Nested
    @DisplayName("Tree shape")
    class TreeShape {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("every block yields SIBLING_COUNT siblings, whatever is empty")
        void siblingCountIsFixed(final String name, final BlockRootTreeHasher hasher) {
            final var digestType = hasher.digestType();
            final var populated = populatedSlots(digestType);
            final var allEmpty = emptySlots(SLOT_COUNT, digestType);
            // The common case: no trace data
            final var noTraceData = populatedSlots(digestType);
            noTraceData[7] = hasher.emptySubtree();

            for (final var slots : List.of(populated, allEmpty, noTraceData)) {
                assertThat(hasher.computeRootAndSiblings(timestampLeaf(digestType), slots)
                                .siblingHashes())
                        .hasSize(SIBLING_COUNT);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("emptying a later branch does not move an earlier branch's path")
        void emptyingASlotDoesNotMoveOthers(final String name, final BlockRootTreeHasher hasher) {
            final var digestType = hasher.digestType();
            final var populated = populatedSlots(digestType);
            final var noTraceData = populated.clone();
            noTraceData[7] = hasher.emptySubtree();

            final var withTrace = hasher.computeRootAndSiblings(timestampLeaf(digestType), populated);
            final var withoutTrace = hasher.computeRootAndSiblings(timestampLeaf(digestType), noTraceData);

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
            final var digestType = hasher.digestType();
            final var slots = populatedSlots(digestType);
            final var timestampLeaf = timestampLeaf(digestType);
            final var actual = hasher.computeRootAndSiblings(timestampLeaf, slots);

            var hash = slots[0];
            for (final var sibling : actual.siblingHashes()) {
                assertThat(sibling.isFirst())
                        .withFailMessage("Every sibling on branch 1's path is a right sibling")
                        .isFalse();
                hash = BlockImplUtils.hashInternalNode(digestType.buildDigest(), hash, sibling.siblingHash());
            }
            hash = BlockImplUtils.hashInternalNode(digestType.buildDigest(), timestampLeaf, hash);

            assertThat(hash).isEqualTo(actual.blockRootHash());
            assertThat(hash.length()).isEqualTo(digestType.digestLength());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("the siblings are the sub-tree roots on branch 1's path")
        void siblingsAreTheSubtreeRootsOnSlotZerosPath(final String name, final BlockRootTreeHasher hasher) {
            final var digestType = hasher.digestType();
            final var slots = populatedSlots(digestType);

            final var expected = List.of(
                    slots[1],
                    StreamingBlockRootTreeHasher.streamedRootOf(
                            digestType::buildDigest, Arrays.copyOfRange(slots, 2, 4)),
                    StreamingBlockRootTreeHasher.streamedRootOf(
                            digestType::buildDigest, Arrays.copyOfRange(slots, 4, 8)),
                    StreamingBlockRootTreeHasher.streamedRootOf(
                            digestType::buildDigest, Arrays.copyOfRange(slots, 8, 16)));

            final var actual = Arrays.stream(hasher.computeRootAndSiblings(timestampLeaf(digestType), slots)
                            .siblingHashes())
                    .map(MerkleSiblingHash::siblingHash)
                    .toList();

            assertThat(actual).containsExactlyElementsOf(expected);
        }
    }

    @Nested
    @DisplayName("Reserved branches")
    class ReservedSlots {
        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = DigestType.class,
                names = {"SHA_384", "SHA_256"})
        @DisplayName("the streaming implementation can assign a reserved branch")
        void streamingSupportsAssigningAReservedSlot(final DigestType digestType) {
            final var hasher = StreamingBlockRootTreeHasher.of(digestType);
            final var slots = emptySlots(SLOT_COUNT, digestType);
            final var withReservedEmpty = hasher.computeBlockRootHash(timestampLeaf(digestType), slots);

            slots[ASSIGNED_SLOT_COUNT] = randomHash(digestType);
            assertThat(hasher.computeBlockRootHash(timestampLeaf(digestType), slots))
                    .isNotEqualTo(withReservedEmpty);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = DigestType.class,
                names = {"SHA_384", "SHA_256"})
        @DisplayName("the caching implementation refuses a populated reserved branch rather than ignoring it")
        void cachedReservedHalfRejectsAPopulatedReservedSlot(final DigestType digestType) {
            final var slots = emptySlots(SLOT_COUNT, digestType);
            slots[ASSIGNED_SLOT_COUNT] = randomHash(digestType);

            assertThatThrownBy(() -> CachedReservedHalfBlockRootTreeHasher.of(digestType)
                            .computeBlockRootHash(timestampLeaf(digestType), slots))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved")
                    .hasMessageContaining("StreamingBlockRootTreeHasher");
        }

        @Test
        @DisplayName("the caching implementation refuses another digest type's empty subtree in a reserved branch")
        void cachedReservedHalfRejectsAnotherDigestTypesEmptySubtree() {
            final var slots = emptySlots(SLOT_COUNT, DigestType.SHA_256);
            slots[SLOT_COUNT - 1] = BlockRootTreeHasher.emptySubtreeFor(DigestType.SHA_384);

            assertThatThrownBy(() -> CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_256)
                            .computeBlockRootHash(timestampLeaf(DigestType.SHA_256), slots))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved");
        }
    }

    @Nested
    @DisplayName("SHA-256 support")
    class Sha256Support {
        @Test
        @DisplayName("streamedRootOf with SHA-256 supplier produces a 32-byte root")
        void streamedRootOfWithSha256Produces32ByteRoot() {
            final var slots = new Bytes[] {Bytes.wrap(new byte[32]), Bytes.wrap(new byte[32])};
            final Bytes root = StreamingBlockRootTreeHasher.streamedRootOf(DigestType.SHA_256::buildDigest, slots);
            assertThat(root.length()).isEqualTo(32);
        }

        @Test
        @DisplayName("SHA-256 and SHA-384 hashers cache different empty values of the right length")
        void sha256AndSha384CacheDifferentEmptyValues() {
            final var sha256 = CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_256);
            final var sha384 = CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_384);
            assertThat(sha256.emptySubtree().length()).isEqualTo(32);
            assertThat(sha256.emptyReservedHalf().length()).isEqualTo(32);
            assertThat(sha256.emptySubtree()).isNotEqualTo(sha384.emptySubtree());
            assertThat(sha256.emptyReservedHalf()).isNotEqualTo(sha384.emptyReservedHalf());
        }

        @Test
        @DisplayName("of() returns one instance per digest type")
        void ofReturnsOneInstancePerDigestType() {
            assertThat(CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_256))
                    .isSameAs(CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_256))
                    .isNotSameAs(CachedReservedHalfBlockRootTreeHasher.of(DigestType.SHA_384));
            assertThat(StreamingBlockRootTreeHasher.of(DigestType.SHA_256).digestType())
                    .isEqualTo(DigestType.SHA_256);
        }
    }

    @Nested
    @DisplayName("Input validation")
    class InputValidation {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("the wrong number of branches is rejected")
        void wrongSlotCountThrows(final String name, final BlockRootTreeHasher hasher) {
            final var tooFew = emptySlots(SLOT_COUNT - 1, hasher.digestType());
            assertThatThrownBy(() -> hasher.computeBlockRootHash(timestampLeaf(hasher.digestType()), tooFew))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("16");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.hedera.node.app.blocks.impl.BlockRootTreeHasherTest#allImplementations")
        @DisplayName("a null branch is rejected rather than silently treated as empty")
        void nullSlotThrows(final String name, final BlockRootTreeHasher hasher) {
            final var withNull = emptySlots(SLOT_COUNT, hasher.digestType());
            withNull[3] = null;
            assertThatThrownBy(() -> hasher.computeBlockRootHash(timestampLeaf(hasher.digestType()), withNull))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Branch 4");
        }
    }

    private static Bytes timestampLeaf(final DigestType digestType) {
        return BlockImplUtils.hashLeaf(digestType.buildDigest(), Timestamp.PROTOBUF.toBytes(A_TIMESTAMP));
    }

    private static Bytes[] emptySlots(final int n, final DigestType digestType) {
        final var slots = new Bytes[n];
        Arrays.fill(slots, BlockRootTreeHasher.emptySubtreeFor(digestType));
        return slots;
    }

    /** All assigned branches populated, reserved branches empty — what a busy block looks like. */
    private static Bytes[] populatedSlots(final DigestType digestType) {
        final var slots = emptySlots(SLOT_COUNT, digestType);
        Arrays.setAll(slots, i -> i < ASSIGNED_SLOT_COUNT ? randomHash(digestType) : slots[i]);
        return slots;
    }

    private static Bytes[] slotsForPresence(final int presenceBitmask, final DigestType digestType) {
        final var slots = emptySlots(SLOT_COUNT, digestType);
        Arrays.setAll(
                slots,
                i -> i < ASSIGNED_SLOT_COUNT && (presenceBitmask & (1 << i)) != 0 ? randomHash(digestType) : slots[i]);
        return slots;
    }

    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    private static Bytes randomHash(final DigestType digestType) {
        final var bytes = new byte[digestType.digestLength()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) RANDOM.nextInt(256);
        }
        return Bytes.wrap(bytes);
    }
}
