// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/**
 * The block root tree with its folds unrolled and the reserved half cached: the production implementation.
 *
 * <p>The tree's shape is fixed, so the pairwise folds are written out rather than streamed — no hasher, no
 * list, no boxing — and the nodes on branch 1's path are named as they are computed instead of being recovered
 * afterwards. Branches 9-16 are empty in every block today, so their root
 * is folded once at class load rather than per block, bringing the cost to nine hashes.
 *
 * <p>Those shortcuts are the only difference from {@link StreamingBlockRootTreeHasher}; the two produce
 * identical hashes, which {@code BlockRootTreeHasherTest} asserts for every combination of populated and
 * empty branches.
 */
public final class CachedReservedHalfBlockRootTreeHasher implements BlockRootTreeHasher {
    /**
     * The instance to use. This class declares no instance fields — the cached reserved half is a static
     * constant — so it is immutable and safe to use concurrently, including by tests running in parallel.
     * It exists only so the implementation can be passed as a {@link BlockRootTreeHasher} without each
     * caller allocating an identical stateless object.
     */
    public static final CachedReservedHalfBlockRootTreeHasher INSTANCE = new CachedReservedHalfBlockRootTreeHasher();

    private CachedReservedHalfBlockRootTreeHasher() {
        // Use INSTANCE; there is no per-instance state to justify another
    }

    /**
     * The root of the reserved branches 9-16, all of which are {@link #EMPTY_SUBTREE}. Folded once here rather
     * than per block, since it cannot vary while those branches are unused.
     *
     * <p>When a reserved branch is first assigned, this shortcut no longer holds and this implementation must
     * fold that half for real, as {@link StreamingBlockRootTreeHasher} already does.
     */
    public static final Bytes EMPTY_RESERVED_HALF = StreamingBlockRootTreeHasher.streamedRootOf(reservedSlots());

    @Override
    public RootAndSiblingHashes computeRootAndSiblings(
            @NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        BlockRootTreeHasher.validate(timestampLeafHash, slots);
        for (int i = ASSIGNED_SLOT_COUNT; i < SLOT_COUNT; i++) {
            if (!EMPTY_SUBTREE.equals(slots[i])) {
                throw new IllegalArgumentException(
                        ("Branch %d is reserved and must be EMPTY_SUBTREE for this implementation; "
                                        + "use StreamingBlockRootTreeHasher to assign it")
                                .formatted(i + 1));
            }
        }

        final var branches12 = BlockImplUtils.hashInternalNode(slots[0], slots[1]);
        final var branches34 = BlockImplUtils.hashInternalNode(slots[2], slots[3]);
        final var branches56 = BlockImplUtils.hashInternalNode(slots[4], slots[5]);
        final var branches78 = BlockImplUtils.hashInternalNode(slots[6], slots[7]);
        final var branches1234 = BlockImplUtils.hashInternalNode(branches12, branches34);
        final var branches5678 = BlockImplUtils.hashInternalNode(branches56, branches78);
        final var assignedHalfRootHash = BlockImplUtils.hashInternalNode(branches1234, branches5678);

        final var subtreesRootHash = BlockImplUtils.hashInternalNode(assignedHalfRootHash, EMPTY_RESERVED_HALF);
        final var blockRootHash = BlockImplUtils.hashInternalNode(timestampLeafHash, subtreesRootHash);

        // The right sibling of branch 1's ancestor at each level, bottom-up
        final var siblings = new MerkleSiblingHash[] {
            new MerkleSiblingHash(false, slots[1]),
            new MerkleSiblingHash(false, branches34),
            new MerkleSiblingHash(false, branches5678),
            new MerkleSiblingHash(false, EMPTY_RESERVED_HALF)
        };
        return new RootAndSiblingHashes(blockRootHash, siblings);
    }

    private static Bytes[] reservedSlots() {
        final var slots = new Bytes[SLOT_COUNT - ASSIGNED_SLOT_COUNT];
        Arrays.fill(slots, EMPTY_SUBTREE);
        return slots;
    }
}
