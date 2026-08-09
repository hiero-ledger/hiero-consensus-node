// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the block root tree. Used by block production, the wrapped record block path, and the independent
 * re-derivations in validation and replay.
 *
 * <h2>Shape</h2>
 * <p>Every block has the same tree:
 * <pre>
 *     blockRootHash = hashInternalNode( hashLeaf(consensusTimestamp), subtreesRootHash )
 * </pre>
 * where {@code subtreesRootHash} is the root over all {@link #SLOT_COUNT} pre-hashed sub-tree roots. A
 * sub-tree with no leaves contributes {@link #EMPTY_SUBTREE}.
 *
 * <p>Because the slot count is a power of two, every slot sits at the same depth in every block. Proof
 * generation relies on this: a slot's path to the root is fixed regardless of which slots are empty.
 *
 * <p>The {@link #ASSIGNED_SLOT_COUNT} assigned slots are folded by {@link IncrementalStreamingHasher} — the
 * same algorithm that builds the item trees filling those slots — and their root is then combined with
 * {@link #EMPTY_RESERVED_HALF}, which is invariant while slots 8-15 are unused.
 *
 * <h2>Slot assignment</h2>
 * <table border="1">
 *   <caption>Block root tree slots</caption>
 *   <tr><th>Slot</th><th>Content</th><th>{@link #EMPTY_SUBTREE} when</th></tr>
 *   <tr><td>0</td><td>previous block root hash</td><td>genesis, by value (block -1 is the empty tree)</td></tr>
 *   <tr><td>1</td><td>root of the tree of all previous block root hashes</td><td>block 0</td></tr>
 *   <tr><td>2</td><td>start of block state root hash</td><td>wrapped record blocks, block 0</td></tr>
 *   <tr><td>3</td><td>consensus headers root</td><td>no consensus header items</td></tr>
 *   <tr><td>4</td><td>input items root</td><td>no input items</td></tr>
 *   <tr><td>5</td><td>output items root</td><td>no output items</td></tr>
 *   <tr><td>6</td><td>state changes root</td><td>no state change items</td></tr>
 *   <tr><td>7</td><td>trace data root</td><td>no trace items &mdash; common</td></tr>
 *   <tr><td>8-15</td><td>reserved</td><td>always, until assigned</td></tr>
 * </table>
 *
 * <p>A wrapped record block uses this same tree, with slots 2, 3, 4, 6 and 7 empty and slot 5 carrying the
 * output items root.
 */
public final class BlockRootTree {
    /** The number of sub-tree roots making up the block root tree's merkle root. */
    public static final int SLOT_COUNT = 16;

    /**
     * The number of slots carrying data. Slots {@code [ASSIGNED_SLOT_COUNT, SLOT_COUNT)} are reserved and
     * contribute {@link #EMPTY_SUBTREE}; assigning one changes only that slot's value, leaving the shape and
     * every other slot's path untouched.
     */
    public static final int ASSIGNED_SLOT_COUNT = 8;

    /** The hash of an empty sub-tree, {@code sha384(0x00)}. */
    public static final Bytes EMPTY_SUBTREE = HASH_OF_ZERO;

    /**
     * The root of the reserved slots 8-15, all of which are {@link #EMPTY_SUBTREE}. Folded once here rather
     * than per block, since it cannot vary while those slots are unused.
     *
     * <p>When a reserved slot is first assigned, this constant must give way to a
     * {@link #streamedRootOf(Bytes[])} of the real slots 8-15.
     */
    public static final Bytes EMPTY_RESERVED_HALF = streamedRootOf(reservedHalfSlots());

    /** A block's root hash together with the sibling hashes on the path from slot 0 up to the root. */
    public record RootAndSiblingHashes(Bytes blockRootHash, MerkleSiblingHash[] siblingHashes) {}

    private BlockRootTree() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Computes a block's root hash and the sibling hashes an indirect proof needs to climb from slot 0 to
     * that root.
     *
     * <p>The returned siblings are ordered bottom-up and are all right siblings: slot 1, then the root of
     * slots 2-3, then the root of slots 4-7, then {@link #EMPTY_RESERVED_HALF}. The first three are recorded
     * by the hasher as it folds, so they are the very nodes that produced the assigned half's root. The
     * timestamp leaf is the block root's left child and is carried separately by the proof, so it does not
     * appear here.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf, {@code hashLeaf(Timestamp.PROTOBUF.toBytes(ts))}
     * @param slots the {@link #ASSIGNED_SLOT_COUNT} assigned sub-tree roots, each already hashed as a leaf or
     *              an internal node and so not hashed again here; use {@link #EMPTY_SUBTREE} for a sub-tree
     *              with no leaves
     * @return the block root hash and the sibling hashes on slot 0's path
     */
    public static RootAndSiblingHashes computeRootAndSiblings(
            @NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        requireNonNull(timestampLeafHash);
        requireNonNull(slots);
        if (slots.length != ASSIGNED_SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "Expected exactly %d slots but got %d".formatted(ASSIGNED_SLOT_COUNT, slots.length));
        }
        for (int i = 0; i < slots.length; i++) {
            requireNonNull(slots[i], "Slot " + i + " must not be null; use EMPTY_SUBTREE for an empty sub-tree");
        }

        // The assigned slots are a power-of-two count, so the hasher folds them to a single root and the
        // siblings on slot 0's path fall out of that same fold
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        for (final var slot : slots) {
            hasher.addNodeByHash(slot.toByteArray());
        }
        final var assignedHalfRootHash = Bytes.wrap(hasher.computeRootHash());
        final var assignedHalfPath = hasher.pathToFirstLeaf();

        final var siblings = new MerkleSiblingHash[assignedHalfPath.size() + 1];
        for (int i = 0; i < assignedHalfPath.size(); i++) {
            siblings[i] = new MerkleSiblingHash(false, assignedHalfPath.get(i));
        }
        siblings[siblings.length - 1] = new MerkleSiblingHash(false, EMPTY_RESERVED_HALF);

        final var subtreesRootHash = BlockImplUtils.hashInternalNode(assignedHalfRootHash, EMPTY_RESERVED_HALF);
        final var blockRootHash = BlockImplUtils.hashInternalNode(timestampLeafHash, subtreesRootHash);
        return new RootAndSiblingHashes(blockRootHash, siblings);
    }

    /**
     * Computes a block's root hash, for callers that do not need the sibling hashes.
     *
     * @param timestampLeafHash the already-hashed timestamp leaf
     * @param slots the {@link #ASSIGNED_SLOT_COUNT} assigned sub-tree roots
     * @return the block root hash
     */
    public static Bytes computeBlockRootHash(@NonNull final Bytes timestampLeafHash, @NonNull final Bytes... slots) {
        return computeRootAndSiblings(timestampLeafHash, slots).blockRootHash();
    }

    /**
     * Computes a block's root hash from an unhashed consensus timestamp.
     *
     * @param consensusTimestamp the block's first consensus timestamp
     * @param slots the {@link #ASSIGNED_SLOT_COUNT} assigned sub-tree roots
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
     * Folds pre-hashed nodes into a single root with {@link IncrementalStreamingHasher}.
     *
     * @param nodes the pre-hashed nodes
     * @return the root hash
     */
    private static Bytes streamedRootOf(final Bytes[] nodes) {
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        for (final var node : nodes) {
            hasher.addNodeByHash(node.toByteArray());
        }
        return Bytes.wrap(hasher.computeRootHash());
    }

    private static Bytes[] reservedHalfSlots() {
        final var slots = new Bytes[SLOT_COUNT - ASSIGNED_SLOT_COUNT];
        Arrays.fill(slots, EMPTY_SUBTREE);
        return slots;
    }
}
