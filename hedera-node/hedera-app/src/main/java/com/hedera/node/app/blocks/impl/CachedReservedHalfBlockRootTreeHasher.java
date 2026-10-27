// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.hiero.base.crypto.DigestType;

/**
 * The block root tree with its folds unrolled and the reserved half cached: the production implementation.
 *
 * <p>The tree's shape is fixed, so the pairwise folds are written out rather than streamed — no hasher, no
 * list, no boxing — and the nodes on branch 1's path are named as they are computed instead of being recovered
 * afterwards. Branches 9-16 are empty in every block today, so their root
 * is folded once per digest type rather than per block, bringing the cost to nine hashes.
 *
 * <p>Those shortcuts are the only difference from {@link StreamingBlockRootTreeHasher}; the two produce
 * identical hashes, which {@code BlockRootTreeHasherTest} asserts for every combination of populated and
 * empty branches.
 */
public final class CachedReservedHalfBlockRootTreeHasher implements BlockRootTreeHasher {
    private static final Map<DigestType, CachedReservedHalfBlockRootTreeHasher> INSTANCES =
            new EnumMap<>(DigestType.class);

    static {
        for (final var digestType : DigestType.values()) {
            INSTANCES.put(digestType, new CachedReservedHalfBlockRootTreeHasher(digestType));
        }
    }

    private final DigestType digestType;
    private final Bytes emptySubtree;

    /**
     * The root of the reserved branches 9-16, all of which are {@link #emptySubtree()}. Folded once per digest
     * type rather than per block, since it cannot vary while those branches are unused.
     *
     * <p>When a reserved branch is first assigned, this shortcut no longer holds and this implementation must
     * fold that half for real, as {@link StreamingBlockRootTreeHasher} already does.
     */
    private final Bytes emptyReservedHalf;

    /**
     * Returns the hasher for the given digest type. Instances hold only values precomputed for their digest
     * type, and every call allocates its own digest, so they are immutable and safe to use concurrently.
     *
     * @param digestType the digest type to build the tree with
     * @return the hasher for that digest type
     */
    public static CachedReservedHalfBlockRootTreeHasher of(@NonNull final DigestType digestType) {
        return INSTANCES.get(requireNonNull(digestType));
    }

    private CachedReservedHalfBlockRootTreeHasher(@NonNull final DigestType digestType) {
        this.digestType = digestType;
        this.emptySubtree = BlockRootTreeHasher.emptySubtreeFor(digestType);
        final var reservedSlots = new Bytes[SLOT_COUNT - ASSIGNED_SLOT_COUNT];
        Arrays.fill(reservedSlots, emptySubtree);
        this.emptyReservedHalf = StreamingBlockRootTreeHasher.streamedRootOf(digestType::buildDigest, reservedSlots);
    }

    @NonNull
    @Override
    public DigestType digestType() {
        return digestType;
    }

    @NonNull
    @Override
    public Bytes emptySubtree() {
        return emptySubtree;
    }

    /**
     * The cached root of the reserved branches 9-16 under this hasher's digest type.
     *
     * @return the reserved half's root
     */
    @NonNull
    public Bytes emptyReservedHalf() {
        return emptyReservedHalf;
    }

    @Override
    public RootAndSiblingHashes computeRootAndSiblings(
            @NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        BlockRootTreeHasher.validate(timestampLeafHash, slots);
        for (int i = ASSIGNED_SLOT_COUNT; i < SLOT_COUNT; i++) {
            if (!emptySubtree.equals(slots[i])) {
                throw new IllegalArgumentException(
                        ("Branch %d is reserved and must be the empty subtree for this implementation; "
                                        + "use StreamingBlockRootTreeHasher to assign it")
                                .formatted(i + 1));
            }
        }

        // Each hash below completes with digest(), which resets the digest, so one instance serves all nine
        final var digest = digestType.buildDigest();
        final var branches12 = BlockImplUtils.hashInternalNode(digest, slots[0], slots[1]);
        final var branches34 = BlockImplUtils.hashInternalNode(digest, slots[2], slots[3]);
        final var branches56 = BlockImplUtils.hashInternalNode(digest, slots[4], slots[5]);
        final var branches78 = BlockImplUtils.hashInternalNode(digest, slots[6], slots[7]);
        final var branches1234 = BlockImplUtils.hashInternalNode(digest, branches12, branches34);
        final var branches5678 = BlockImplUtils.hashInternalNode(digest, branches56, branches78);
        final var assignedHalfRootHash = BlockImplUtils.hashInternalNode(digest, branches1234, branches5678);

        final var subtreesRootHash = BlockImplUtils.hashInternalNode(digest, assignedHalfRootHash, emptyReservedHalf);
        final var blockRootHash = BlockImplUtils.hashInternalNode(digest, timestampLeafHash, subtreesRootHash);

        // The right sibling of branch 1's ancestor at each level, bottom-up
        final var siblings = new MerkleSiblingHash[] {
            new MerkleSiblingHash(false, slots[1]),
            new MerkleSiblingHash(false, branches34),
            new MerkleSiblingHash(false, branches5678),
            new MerkleSiblingHash(false, emptyReservedHalf)
        };
        return new RootAndSiblingHashes(blockRootHash, siblings);
    }
}
