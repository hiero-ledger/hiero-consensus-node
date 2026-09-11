// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Builds the block root tree from its {@link #SLOT_COUNT} branches.
 *
 * <p>Every block has the same tree:
 * <pre>
 *     blockRootHash = hashInternalNode( hashLeaf(consensusTimestamp), subtreesRootHash )
 * </pre>
 * where {@code subtreesRootHash} is the root over all {@link #SLOT_COUNT} pre-hashed branch roots. A branch
 * with no leaves contributes {@link #EMPTY_SUBTREE}.
 *
 * <p>Because the branch count is a power of two, every branch sits at the same depth in every block. Proof
 * generation relies on this: a branch's path to the root is fixed regardless of which branches are empty.
 *
 * <p>Two implementations satisfy this contract and are freely interchangeable.
 * {@link StreamingBlockRootTreeHasher} states the algorithm plainly, folding every branch with
 * {@link IncrementalStreamingHasher}. {@link CachedReservedHalfBlockRootTreeHasher} is what production uses;
 * it reaches the same hashes by caching the invariant root of the reserved branches and unrolling the folds.
 * {@code BlockRootTreeHasherTest} runs the whole contract against both and asserts they agree.
 *
 * @see BlockRootTree for the block-stream-facing entry point, which supplies the reserved branches
 */
public interface BlockRootTreeHasher {
    /** The number of branches making up the block root tree's merkle root. */
    int SLOT_COUNT = 16;

    /**
     * The number of branches carrying data. Branches 9-16 are reserved and contribute
     * {@link #EMPTY_SUBTREE}; assigning one changes only that branch's value, leaving the shape and every
     * other branch's path untouched.
     */
    int ASSIGNED_SLOT_COUNT = 8;

    /** The number of sibling hashes on the path from branch 1 to the block root, one per level. */
    int SIBLING_COUNT = Integer.numberOfTrailingZeros(SLOT_COUNT);

    /** The hash of an empty branch, {@code sha384(0x00)}. */
    Bytes EMPTY_SUBTREE = HASH_OF_ZERO;

    /** A block's root hash together with the sibling hashes on the path from branch 1 up to the root. */
    record RootAndSiblingHashes(Bytes blockRootHash, MerkleSiblingHash[] siblingHashes) {}

    /**
     * Computes a block's root hash and the sibling hashes an indirect proof needs to climb from branch 1 to
     * that root.
     *
     * <p>A block is not always signed directly. When it is proven indirectly, the proof starts from branch 1
     * (the previous block root hash) and climbs to the block root, so it must carry the hash of the sibling
     * met at each level — the nodes a verifier cannot recompute because it does not hold the other branches.
     * The block root tree is a perfect tree, so that is exactly {@link #SIBLING_COUNT} of them, whatever a
     * block contains. See {@code BlockStateProofGenerator}.
     *
     * <p>Branch 1 is the leftmost leaf, so every sibling on its path is a right sibling: branch 2, then the
     * root of branches 3-4, then the root of branches 5-8, then the root of branches 9-16. The timestamp
     * leaf is the block root's left child and is carried separately by the proof, so it does not appear here.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf, {@code hashLeaf(Timestamp.PROTOBUF.toBytes(ts))}
     * @param slots all {@link #SLOT_COUNT} branch roots, each already hashed as a leaf or an internal node
     *              and so not hashed again here; use {@link #EMPTY_SUBTREE} for a branch with no leaves
     * @return the block root hash and the sibling hashes on branch 1's path
     */
    RootAndSiblingHashes computeRootAndSiblings(@NonNull Bytes timestampLeafHash, @NonNull Bytes... slots);

    /**
     * Computes a block's root hash, for callers that do not need the sibling hashes.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf
     * @param slots all {@link #SLOT_COUNT} branch roots
     * @return the block root hash
     */
    default Bytes computeBlockRootHash(@NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        return computeRootAndSiblings(timestampLeafHash, slots).blockRootHash();
    }

    /**
     * Checks the arguments every implementation requires: a timestamp leaf, and exactly {@link #SLOT_COUNT}
     * non-null branch roots.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf
     * @param slots the branch roots to validate
     * @throws IllegalArgumentException if the branch count is wrong
     * @throws NullPointerException if the timestamp leaf or any branch is null
     */
    static void validate(final Bytes timestampLeafHash, final Bytes[] slots) {
        requireNonNull(timestampLeafHash, "timestampLeafHash must not be null");
        requireNonNull(slots, "branch roots must not be null");
        if (slots.length != SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "Expected exactly %d branch roots but got %d".formatted(SLOT_COUNT, slots.length));
        }
        for (int i = 0; i < slots.length; i++) {
            requireNonNull(slots[i], "Branch " + (i + 1) + " must not be null; use EMPTY_SUBTREE for an empty branch");
        }
    }
}
