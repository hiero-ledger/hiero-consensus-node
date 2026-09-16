// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.ASSIGNED_SLOT_COUNT;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.EMPTY_SUBTREE;
import static com.hedera.node.app.blocks.impl.BlockRootTreeHasher.SLOT_COUNT;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.blocks.impl.BlockRootTreeHasher.RootAndSiblingHashes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/**
 * The block-stream-facing entry point to the block root tree, used by block production, the wrapped record
 * block path.
 *
 * <p>Callers supply only the {@link BlockRootTreeHasher#ASSIGNED_SLOT_COUNT} branches that carry data; this
 * class pads the reserved branches with {@link BlockRootTreeHasher#EMPTY_SUBTREE} and delegates to
 * {@link CachedReservedHalfBlockRootTreeHasher}. It is the single place that knows which branches are
 * assigned, so it is the place to change when a reserved branch is first used.
 *
 * <table border="1">
 *   <caption>Block root tree branches</caption>
 *   <tr><th>Branch</th><th>Content</th><th>{@code EMPTY_SUBTREE} when</th></tr>
 *   <tr><td>1</td><td>previous block root hash</td><td>genesis, by value (block -1 is the empty tree)</td></tr>
 *   <tr><td>2</td><td>root of the tree of all previous block root hashes</td><td>block 0</td></tr>
 *   <tr><td>3</td><td>start of block state root hash</td><td>wrapped record blocks, block 0</td></tr>
 *   <tr><td>4</td><td>consensus headers root</td><td>no consensus header items</td></tr>
 *   <tr><td>5</td><td>input items root</td><td>no input items</td></tr>
 *   <tr><td>6</td><td>output items root</td><td>no output items</td></tr>
 *   <tr><td>7</td><td>state changes root</td><td>no state change items</td></tr>
 *   <tr><td>8</td><td>trace data root</td><td>no trace items &mdash; common</td></tr>
 *   <tr><td>9-16</td><td>reserved</td><td>always, until assigned</td></tr>
 * </table>
 *
 * <p>A wrapped record block uses this same tree, with branches 3, 4, 5, 7 and 8 empty and branch 6 carrying
 * the output items root.
 *
 * <h2>Assigning a reserved branch</h2>
 * <p>The tree's shape does not change when branch 9 is first used — its value simply stops being
 * {@link BlockRootTreeHasher#EMPTY_SUBTREE}, and no other branch moves. The code does need three changes,
 * all of them here or one level down, because this class fixes the caller-facing arity at
 * {@link BlockRootTreeHasher#ASSIGNED_SLOT_COUNT} and {@link CachedReservedHalfBlockRootTreeHasher} caches
 * the reserved half:
 * <ol>
 *   <li>Raise {@link BlockRootTreeHasher#ASSIGNED_SLOT_COUNT}, so callers pass the extra branch root and
 *       {@link #withReservedSlots} pads one fewer.</li>
 *   <li>Point {@code HASHER} at {@link StreamingBlockRootTreeHasher}, which folds all sixteen branches and
 *       assumes nothing about which are empty, or extend
 *       {@link CachedReservedHalfBlockRootTreeHasher} to fold the branches it no longer knows to be empty.
 *       That implementation rejects a populated reserved branch rather than silently hashing the cached
 *       value, so this cannot be missed.</li>
 *   <li>Update the callers to supply the new branch root.</li>
 * </ol>
 * The wire format, the sibling count and every existing branch's proof path are unaffected.
 */
public final class BlockRootTree {
    /**
     * The hash of an empty branch, to pass for any assigned branch with no content. Re-exported from
     * {@link BlockRootTreeHasher#EMPTY_SUBTREE} so callers need only this class.
     */
    public static final Bytes EMPTY_SUBTREE = BlockRootTreeHasher.EMPTY_SUBTREE;

    private static final BlockRootTreeHasher HASHER = CachedReservedHalfBlockRootTreeHasher.INSTANCE;

    private BlockRootTree() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Computes a block's root hash and the sibling hashes an indirect proof needs to climb from branch 1 to
     * that root.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf, {@code hashLeaf(Timestamp.PROTOBUF.toBytes(ts))}
     * @param slots the {@link BlockRootTreeHasher#ASSIGNED_SLOT_COUNT} assigned branch roots, each already
     *              hashed as a leaf or an internal node and so not hashed again here; use
     *              {@link BlockRootTreeHasher#EMPTY_SUBTREE} for a branch with no leaves
     * @return the block root hash and the sibling hashes on branch 1's path
     */
    public static RootAndSiblingHashes computeRootAndSiblings(
            @NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        return HASHER.computeRootAndSiblings(timestampLeafHash, withReservedSlots(slots));
    }

    /**
     * Computes a block's root hash, for callers that do not need the sibling hashes.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf
     * @param slots the assigned branch roots
     * @return the block root hash
     */
    public static Bytes computeBlockRootHash(@NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        return HASHER.computeBlockRootHash(timestampLeafHash, withReservedSlots(slots));
    }

    /**
     * Computes a block's root hash from an unhashed consensus timestamp.
     *
     * @param consensusTimestamp the block's first consensus timestamp
     * @param slots the assigned branch roots
     * @return the block root hash
     */
    public static Bytes computeBlockRootHash(
            @NonNull final Timestamp consensusTimestamp, @NonNull final Bytes... slots) {
        return computeBlockRootHash(hashTimestampLeaf(consensusTimestamp), slots);
    }

    /**
     * Hashes a consensus timestamp as the block root's left-hand leaf.
     *
     * @param consensusTimestamp the timestamp to hash
     * @return the leaf hash
     */
    public static Bytes hashTimestampLeaf(@NonNull final Timestamp consensusTimestamp) {
        return BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(requireNonNull(consensusTimestamp)));
    }

    /**
     * Expands the assigned branches to the full tree by padding the reserved branches with
     * {@link BlockRootTreeHasher#EMPTY_SUBTREE}.
     *
     * @param assignedSlots the assigned branch roots
     * @return all {@link BlockRootTreeHasher#SLOT_COUNT} branch roots
     */
    private static Bytes[] withReservedSlots(final Bytes[] assignedSlots) {
        requireNonNull(assignedSlots, "branch roots must not be null");
        if (assignedSlots.length != ASSIGNED_SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "Expected exactly %d branch roots but got %d".formatted(ASSIGNED_SLOT_COUNT, assignedSlots.length));
        }
        final var slots = new Bytes[SLOT_COUNT];
        System.arraycopy(assignedSlots, 0, slots, 0, ASSIGNED_SLOT_COUNT);
        Arrays.fill(slots, ASSIGNED_SLOT_COUNT, SLOT_COUNT, EMPTY_SUBTREE);
        return slots;
    }

    /**
     * A convenience for building the assigned branches of a block whose sub-trees are mostly empty.
     *
     * @return an array of {@link BlockRootTreeHasher#ASSIGNED_SLOT_COUNT} empty branch hashes
     */
    public static Bytes[] emptyAssignedSlots() {
        final var slots = new Bytes[ASSIGNED_SLOT_COUNT];
        Arrays.fill(slots, EMPTY_SUBTREE);
        return slots;
    }

    /** The {@link MerkleSiblingHash} count every block's proof carries; see {@link BlockRootTreeHasher}. */
    public static int siblingCount() {
        return BlockRootTreeHasher.SIBLING_COUNT;
    }
}
