// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import static com.hedera.node.app.hapi.utils.blocks.HashUtils.computeBlockItemLeafHash;
import static com.hedera.node.app.hapi.utils.blocks.HashUtils.computeSingleChildHash;
import static com.hedera.node.app.hapi.utils.blocks.HashUtils.computeStateItemLeafHash;
import static com.hedera.node.app.hapi.utils.blocks.HashUtils.computeTimestampLeafHash;
import static com.hedera.node.app.hapi.utils.blocks.HashUtils.joinHashes;
import static com.hedera.node.app.hapi.utils.blocks.HashUtils.newMessageDigest;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.binary.MerkleProof;
import com.swirlds.state.binary.SiblingHash;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builder and utility helper for a single Merkle Path (i.e. a standard single-leaf Merkle proof) that is
 * later merged into an accumulating state proof.
 *
 * <p>A Merkle Path in this code base represents the authentication path from either:
 * <ul>
 *   <li>A concrete leaf (represented by leaf bytes in a {@link MerklePath}), or</li>
 *   <li>A pre-computed hash (for internal-only path segments), or</li>
 *   <li>An internal starting hash ("startHash") when the path is used purely as an internal connector after
 *       merging.</li>
 * </ul>
 * up to the Merkle root.
 *
 * <p>Each sibling node records the hash of the sibling at that level and whether the sibling is located on the
 * left. Sibling nodes are stored from <em>nearest the leaf (index 0) to the root (last index)</em>. This direction
 * is convenient for incremental extension while computing hashes upward.
 *
 * <p>This builder maintains an incremental cache of inner node hashes (including the base leaf/hash/start hash
 * at index 0 and the root at the last index). This allows O(1) access to any intermediate hash needed while
 * merging multiple paths into an aggregated state proof.</p>
 */
public final class MerklePathBuilder {

    private final MessageDigest digest = newMessageDigest();

    private Bytes timestampLeaf;
    private Bytes blockItemLeaf;
    private Bytes stateItemLeaf;
    private Bytes hash;
    private byte[] startHash;
    private final List<SiblingNode> siblingNodes = new ArrayList<>();
    private final List<byte[]> innerNodeHashes = new ArrayList<>();
    private byte[] rootHash;
    private int nextPathIndex = -1;

    /**
     * Constructs an empty Merkle path builder. One of {@link #setStateItemLeaf(Bytes)}, {@link #setBlockItemLeaf(Bytes)},
     * {@link #setTimestampLeaf(Bytes)}, {@link #setHash(Bytes)} or a merge operation that produces {@link #startHash}
     * must be invoked before hash accessors are used.
     */
    public MerklePathBuilder() {}

    /**
     * Creates a new builder from a State API {@link MerkleProof}.
     *
     * @param merkleProof the Merkle proof obtained from the state
     * @return a new builder instance
     */
    @NonNull
    public static MerklePathBuilder fromStateApi(@NonNull final MerkleProof merkleProof) {
        requireNonNull(merkleProof, "merkleProof must not be null");
        final var builder = new MerklePathBuilder();
        builder.setStateItemLeaf(merkleProof.stateItem());
        builder.setSiblingNodes(convertSiblingHashes(merkleProof.siblingHashes()));
        // If the proof provides cached inner-parent hashes, use them only when the provided root hash
        // matches the root hash implied by the leaf + sibling hashes. This preserves cryptographic
        // correctness while still allowing detection of mismatched intermediate hashes during merges.
        final var innerParentHashes = merkleProof.innerParentHashes();
        if (innerParentHashes != null && !innerParentHashes.isEmpty()) {
            final int expectedSize = builder.siblingNodes.size() + 1;
            if (innerParentHashes.size() == expectedSize) {
                final byte[] providedRoot =
                        innerParentHashes.get(expectedSize - 1).copyToByteArray();
                if (Arrays.equals(providedRoot, builder.rootHash)) {
                    builder.innerNodeHashes.clear();
                    for (final var hash : innerParentHashes) {
                        builder.innerNodeHashes.add(hash.copyToByteArray());
                    }
                    builder.rootHash = providedRoot;
                }
            }
        }
        return builder;
    }

    /**
     * Converts {@link SiblingHash} instances to protobuf {@link SiblingNode}s in leaf-to-root order.
     */
    @NonNull
    private static List<SiblingNode> convertSiblingHashes(@NonNull final List<SiblingHash> siblingHashes) {
        requireNonNull(siblingHashes, "siblingHashes must not be null");
        final var result = new ArrayList<SiblingNode>(siblingHashes.size());
        for (final var siblingHash : siblingHashes) {
            result.add(SiblingNode.newBuilder()
                    .isLeft(siblingHash.isLeft())
                    .hash(Bytes.wrap(siblingHash.hash().copyToByteArray()))
                    .build());
        }
        return result;
    }

    /** @return true if this path starts from a concrete leaf */
    public boolean hasLeaf() {
        return timestampLeaf != null || blockItemLeaf != null || stateItemLeaf != null;
    }

    /** @return explicit starting hash if set (null if leaf or startHash is used) */
    public Bytes getHash() {
        return hash;
    }

    /** @return cached root hash for this path */
    public byte[] getRootHash() {
        return rootHash;
    }

    /**
     * Assigns the parent path index used when serializing multiple paths in an aggregated state proof. A value of -1
     * denotes that this path is (currently) considered a root in the forest of paths prior to final linking.
     *
     * @param index parent path index or -1 for root
     * @return this builder
     */
    public MerklePathBuilder setNextPathIndex(final int index) {
        this.nextPathIndex = index;
        return this;
    }

    /**
     * Sets the state-item leaf for this path. Recomputes all inner node hashes using existing sibling nodes.
     * The leaf takes precedence over a previously set {@link #hash} or {@link #startHash}.
     *
     * @param newLeaf the concrete state item leaf bytes
     * @return this builder
     */
    public MerklePathBuilder setStateItemLeaf(@NonNull final Bytes newLeaf) {
        this.stateItemLeaf = requireNonNull(newLeaf, "stateItemLeaf must not be null");
        this.blockItemLeaf = null;
        this.timestampLeaf = null;
        this.hash = null;
        this.startHash = null;
        recomputeInnerNodeHashes();
        return this;
    }

    /**
     * Sets the block-item leaf for this path. Recomputes all inner node hashes using existing sibling nodes.
     * The leaf takes precedence over a previously set {@link #hash} or {@link #startHash}.
     *
     * @param newLeaf the concrete block item leaf bytes
     * @return this builder
     */
    public MerklePathBuilder setBlockItemLeaf(@NonNull final Bytes newLeaf) {
        this.blockItemLeaf = requireNonNull(newLeaf, "blockItemLeaf must not be null");
        this.stateItemLeaf = null;
        this.timestampLeaf = null;
        this.hash = null;
        this.startHash = null;
        recomputeInnerNodeHashes();
        return this;
    }

    /**
     * Sets the timestamp leaf for this path. Recomputes all inner node hashes using existing sibling nodes.
     * The leaf takes precedence over a previously set {@link #hash} or {@link #startHash}.
     *
     * @param newLeaf the concrete timestamp leaf bytes
     * @return this builder
     */
    public MerklePathBuilder setTimestampLeaf(@NonNull final Bytes newLeaf) {
        this.timestampLeaf = requireNonNull(newLeaf, "timestampLeaf must not be null");
        this.stateItemLeaf = null;
        this.blockItemLeaf = null;
        this.hash = null;
        this.startHash = null;
        recomputeInnerNodeHashes();
        return this;
    }

    /**
     * Set an explicit starting hash (when the actual leaf content is not present). This is typically used for
     * representing a Merkle path segment derived from elsewhere or for internal-only nodes with a known root hash.
     * Recomputes inner hashes with current siblings.
     *
     * @param newHash base hash representing the starting sub-tree root
     * @return this builder
     */
    public MerklePathBuilder setHash(@NonNull final Bytes newHash) {
        this.hash = requireNonNull(newHash, "hash must not be null");
        this.timestampLeaf = null;
        this.blockItemLeaf = null;
        this.stateItemLeaf = null;
        this.startHash = null;
        recomputeInnerNodeHashes();
        return this;
    }

    /**
     * Replaces the list of sibling nodes (ordered from leaf to root) and recomputes cached hashes.
     *
     * @param siblings new ordered sibling list
     * @return this builder
     */
    public MerklePathBuilder setSiblingNodes(@NonNull final List<SiblingNode> siblings) {
        requireNonNull(siblings, "siblings must not be null");
        siblingNodes.clear();
        siblingNodes.addAll(siblings);
        if (hasBaseHash()) {
            recomputeInnerNodeHashes();
        }
        return this;
    }

    /**
     * Appends multiple sibling nodes (in the given order, which must be leaf->root order continuation) and extends hashes.
     *
     * @param siblings siblings to append
     * @return this builder
     */
    public MerklePathBuilder appendSiblingNodes(@NonNull final List<SiblingNode> siblings) {
        requireNonNull(siblings, "siblings must not be null");
        if (siblings.isEmpty()) {
            return this;
        }
        siblingNodes.addAll(siblings);
        if (hasBaseHash()) {
            extendInnerNodeHashes(innerNodeHashes.size() - 1);
        }
        return this;
    }

    /**
     * Appends a single sibling node and extends cached hashes if possible.
     *
     * @param sibling sibling to append
     * @return this builder
     */
    public MerklePathBuilder appendSiblingNode(@NonNull final SiblingNode sibling) {
        requireNonNull(sibling, "sibling must not be null");
        siblingNodes.add(sibling);
        if (hasBaseHash()) {
            extendInnerNodeHashes(innerNodeHashes.size() - 1);
        }
        return this;
    }

    /** @return defensive copy of sibling nodes (leaf to root order) */
    public List<SiblingNode> getSiblingNodes() {
        return new ArrayList<>(siblingNodes);
    }

    /** @return defensive copy of cached inner node hashes (index 0 is base hash, last is root) */
    public List<byte[]> getInnerNodeHashes() {
        return new ArrayList<>(innerNodeHashes);
    }

    /**
     * Builds an immutable {@link MerklePath} instance from the current state.
     *
     * @return immutable MerklePath
     */
    public MerklePath build() {
        final var builder = MerklePath.newBuilder().nextPathIndex(nextPathIndex);
        if (stateItemLeaf != null) {
            builder.stateItemLeaf(stateItemLeaf);
        } else if (blockItemLeaf != null) {
            builder.blockItemLeaf(blockItemLeaf);
        } else if (timestampLeaf != null) {
            builder.timestampLeaf(timestampLeaf);
        } else if (hash != null) {
            builder.hash(hash);
        }
        if (!siblingNodes.isEmpty()) {
            builder.siblings(siblingNodes);
        }
        return builder.build();
    }

    /**
     * Produces a new builder representing only the last {@code prefixLength} sibling nodes closest to the root.
     * The new builder uses an internal start hash equal to the inner node hash immediately preceding the
     * retained siblings. This effectively converts a leaf-based path into a higher (internal) starting point.
     *
     * @param prefixLength number of top (root-near) siblings to retain
     * @return new builder for that internal prefix path
     */
    public MerklePathBuilder prefixMerklePathBuilder(final int prefixLength) {
        if (prefixLength < 0 || prefixLength > siblingNodes.size()) {
            throw new IllegalArgumentException("Prefix length out of bounds");
        }
        if (prefixLength == 0) {
            final var root = getRootHash();
            return new MerklePathBuilder().setStartHash(root);
        }
        final int prefixStartIndex = siblingNodes.size() - prefixLength;
        final var subset = new ArrayList<>(siblingNodes.subList(prefixStartIndex, siblingNodes.size()));
        final var start = innerNodeHashes.get(prefixStartIndex);
        return new MerklePathBuilder().setStartHash(start).setSiblingNodes(subset);
    }

    /**
     * Produces a new builder equal to this one with a number of root-most sibling nodes removed. This is used
     * during merging to isolate the unmatched remainder of a path beneath a branch point. The removed portion
     * (a suffix in leaf->root ordering) corresponds to a prefix near the root.
     *
     * @param size number of siblings to remove from the root side (0 is no-op)
     * @return new pruned builder (or this if size &lt;= 0)
     */
    public MerklePathBuilder pruneFromRoot(final int size) {
        if (size <= 0) {
            return this;
        }
        if (size > siblingNodes.size()) {
            throw new IllegalArgumentException("Pruning more sibling nodes than exist");
        }
        final var newBuilder = new MerklePathBuilder();
        if (stateItemLeaf != null) {
            newBuilder.setStateItemLeaf(stateItemLeaf);
        } else if (blockItemLeaf != null) {
            newBuilder.setBlockItemLeaf(blockItemLeaf);
        } else if (timestampLeaf != null) {
            newBuilder.setTimestampLeaf(timestampLeaf);
        } else if (hash != null) {
            newBuilder.setHash(hash);
        } else if (startHash != null) {
            newBuilder.setStartHash(startHash);
        }
        final var newSiblings = siblingNodes.subList(0, siblingNodes.size() - size);
        newBuilder.setSiblingNodes(new ArrayList<>(newSiblings));
        return newBuilder;
    }

    /**
     * Retrieves an inner node hash at a given index (0-based from the base toward the root).
     *
     * @param index position in the inner node hash list
     * @return hash bytes
     */
    public byte[] getInnerNodeHash(final int index) {
        if (index < 0 || index >= innerNodeHashes.size()) {
            throw new IndexOutOfBoundsException("Index out of bounds for inner node hashes");
        }
        return innerNodeHashes.get(index);
    }

    /**
     * Computes the root hash when this path represents a parent with exactly one child (single child hash).
     *
     * @param childHash the child's hash
     * @return resulting root hash
     */
    public byte[] computeRootHashFromSingleChild(@NonNull final byte[] childHash) {
        requireNonNull(childHash, "childHash must not be null");
        final var h = computeSingleChildHash(digest, childHash);
        return computeRootHashFrom(h);
    }

    /**
     * Computes the root hash given two children hashes. This is equivalent to re-rooting this builder's path
     * above the supplied children.
     *
     * @param leftHash left child hash
     * @param rightHash right child hash
     * @return resulting root hash
     */
    public byte[] computeRootHashFromBothChildren(@NonNull final byte[] leftHash, @NonNull final byte[] rightHash) {
        requireNonNull(leftHash, "leftHash must not be null");
        requireNonNull(rightHash, "rightHash must not be null");
        final var h = joinHashes(digest, leftHash, rightHash);
        return computeRootHashFrom(h);
    }

    private boolean hasBaseHash() {
        return hasLeaf() || hash != null || startHash != null;
    }

    private MerklePathBuilder setStartHash(@NonNull final byte[] newStartHash) {
        this.startHash = requireNonNull(newStartHash, "startHash must not be null");
        this.timestampLeaf = null;
        this.blockItemLeaf = null;
        this.stateItemLeaf = null;
        this.hash = null;
        recomputeInnerNodeHashes();
        return this;
    }

    private void recomputeInnerNodeHashes() {
        if (!hasBaseHash()) {
            return;
        }
        final byte[] baseHash;
        if (stateItemLeaf != null) {
            baseHash = computeStateItemLeafHash(digest, stateItemLeaf);
        } else if (blockItemLeaf != null) {
            baseHash = computeBlockItemLeafHash(digest, blockItemLeaf);
        } else if (timestampLeaf != null) {
            baseHash = computeTimestampLeafHash(digest, timestampLeaf);
        } else if (hash != null) {
            baseHash = hash.toByteArray();
        } else {
            baseHash = startHash;
        }
        computeRootHashFrom(baseHash);
    }

    /**
     * Computes the root hash by iteratively combining the base hash with sibling nodes.
     *
     * <p>This method implements the core Merkle path computation algorithm:
     * <ol>
     *   <li>Start with the base hash (leaf, explicit hash, or start hash)</li>
     *   <li>For each sibling node in leaf-to-root order:
     *     <ul>
     *       <li>If sibling hash is empty (0-length), promote current hash as single-child node</li>
     *       <li>Otherwise, combine current hash with sibling hash based on position (left/right)</li>
     *     </ul>
     *   </li>
     *   <li>Cache each intermediate hash for efficient merging</li>
     * </ol>
     *
     * <p>The algorithm ensures O(n) time complexity where n is the number of siblings,
     * with incremental caching allowing O(1) access to any intermediate hash.
     *
     * @param baseHash the starting hash for the computation
     * @return the computed root hash
     */
    private byte[] computeRootHashFrom(@NonNull byte[] baseHash) {
        innerNodeHashes.clear();
        innerNodeHashes.add(baseHash);
        byte[] current = baseHash;
        for (final var sibling : siblingNodes) {
            final var siblingBytes = sibling.hash().toByteArray();
            if (siblingBytes.length == 0) {
                current = computeSingleChildHash(digest, current);
            } else if (sibling.isLeft()) {
                current = joinHashes(digest, siblingBytes, current);
            } else {
                current = joinHashes(digest, current, siblingBytes);
            }
            innerNodeHashes.add(current);
        }
        rootHash = current;
        return rootHash;
    }

    private void extendInnerNodeHashes(final int startIndex) {
        if (!hasBaseHash()) {
            return;
        }
        int index = Math.max(startIndex, 0);
        if (innerNodeHashes.isEmpty()) {
            recomputeInnerNodeHashes();
            index = 0;
        }
        byte[] current = innerNodeHashes.get(innerNodeHashes.size() - 1);
        for (; index < siblingNodes.size(); index++) {
            final var sibling = siblingNodes.get(index);
            final var siblingBytes = sibling.hash().toByteArray();
            if (siblingBytes.length == 0) {
                current = computeSingleChildHash(digest, current);
            } else if (sibling.isLeft()) {
                current = joinHashes(digest, siblingBytes, current);
            } else {
                current = joinHashes(digest, current, siblingBytes);
            }
            innerNodeHashes.add(current);
        }
        rootHash = current;
    }
}
