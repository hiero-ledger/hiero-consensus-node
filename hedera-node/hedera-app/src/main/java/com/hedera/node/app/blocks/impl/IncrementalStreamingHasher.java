// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.List;

/**
 * /**
 * A memory-efficient Merkle tree hasher that computes root hashes in a streaming fashion.
 *
 * <p>This implementation follows the Streaming Binary Merkle Tree algorithm from the Block &amp; State
 * Merkle Tree Design specification. It supports adding leaves one at a time and computes the root
 * hash without storing the entire tree in memory.
 *
 * <h2>Algorithm Overview</h2>
 * <p>The streaming algorithm maintains a compact list of "pending subtree roots" representing
 * incomplete portions of the tree. As each leaf is added:
 * <ol>
 *   <li>The leaf is hashed with prefix {@code 0x00}</li>
 *   <li>Whenever two sibling nodes are complete, they are immediately combined into an
 *       internal node with prefix {@code 0x02}</li>
 *   <li>This folding propagates upward until no more pairs can be combined</li>
 * </ol>
 *
 * <h2>Memory Efficiency</h2>
 * <p>For a tree with n leaves, only O(log n) intermediate hashes are stored. Specifically,
 * the number of pending subtree roots equals {@code Integer.bitCount(leafCount)}.
 *
 * <h2>Example: Building a 5-leaf tree</h2>
 * <pre>
 * Step 1: Add L0     → hashList: [L0]
 * Step 2: Add L1     → L0+L1 pair → NodeA = hash(0x02 || L0 || L1)
 *                    → hashList: [NodeA]
 * Step 3: Add L2     → hashList: [NodeA, L2]
 * Step 4: Add L3     → L2+L3 pair → NodeB
 *                    → NodeA+NodeB pair → NodeC
 *                    → hashList: [NodeC]
 * Step 5: Add L4     → hashList: [NodeC, L4]
 * Root: hash(0x02 || NodeC || L4)
 * </pre>
 *
 * <h2>Persistence</h2>
 * <p>The intermediate state can be saved and loaded, allowing tree construction to be
 * paused and resumed across process restarts.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. It is designed for single-threaded use.
 *
 * <p>This class is based on Hiero Block Node's {@code StreamingHasher}, located at
 * <a href="https://github.com/hiero-ledger/hiero-block-node/blob/main/tools-and-tests/tools/src/main/java/org/hiero/block/tools/blocks/model/hashing/StreamingHasher.java">this link</a>.
 */
public class IncrementalStreamingHasher {

    /** The hashing algorithm used for computing the hashes. */
    private final MessageDigest digest;
    /** A list to store intermediate hashes as we build the tree. */
    private final LinkedList<byte[]> hashList = new LinkedList<>();
    /** The count of leaves in the tree. */
    private long leafCount;

    /** Create a StreamingHasher with an existing intermediate hashing state. */
    public IncrementalStreamingHasher(
            final MessageDigest digest, List<byte[]> intermediateHashingState, final long leafCount) {
        if (digest == null) {
            throw new IllegalArgumentException("digest must not be null");
        }
        this.digest = digest;
        // These byte arrays should have already been hashed, so we can add them directly
        this.hashList.addAll(intermediateHashingState);
        this.leafCount = leafCount;
    }

    /**
     * Adds a new leaf to the Merkle tree.
     *
     * <p>The leaf data is hashed with prefix {@code 0x00}, then the streaming fold-up
     * algorithm combines any complete sibling pairs into internal nodes.
     *
     * <p>Time complexity: O(log n) hash operations in the worst case, O(1) amortized.
     *
     * @param data the raw data for the new leaf
     */
    public void addLeaf(final byte[] data) {
		var leafHash = BlockImplUtils.hashBlockItemLeaf(data);
        addNodeByHash(leafHash.toByteArray());
    }

	public void addBlockItemLeaf(final byte[] data) {
		var leafHash = BlockImplUtils.hashBlockItemLeaf(data);
		addNodeByHash(leafHash.toByteArray());
	}

    /**
     * Add a pre-hashed node to the Merkle tree. This is needed for a tree of other trees. Where each node at the
     * bottom of this tree is the root hash of another tree.
     *
     * @param hash the 48-byte SHA-384 hash of the node to add (must already include the prefixing)
     */
    public void addNodeByHash(byte[] hash) {
        hashList.add(hash);
        // Fold up: combine sibling pairs while the current position is odd
        for (long n = leafCount; (n & 1L) == 1; n >>= 1) {
            final byte[] y = hashList.removeLast();
            final byte[] x = hashList.removeLast();
            hashList.add(hashInternalNode(x, y));
        }
        leafCount++;
    }

    /**
     * Computes the Merkle tree root hash from the current state.
     *
     * <p>This method folds all pending subtree roots from right to left to produce
     * the final root hash. The internal state is not modified, so more leaves can
     * be added after calling this method.
     *
     * <p>Time complexity: O(log n) where n is the leaf count.
     *
     * <p>For an empty tree (no leaves added), this method returns the predefined
     * {@link BlockStreamManager#HASH_OF_ZERO} which is {@code sha384Hash(new byte[]{0x00})}.
     *
     * @return the 48-byte SHA-384 Merkle tree root hash, or {@link BlockStreamManager#HASH_OF_ZERO_BYTES}
     *         if no leaves have been added
     */
    public byte[] computeRootHash() {
        if (hashList.isEmpty()) {
            // This value is precomputed as the hash of an empty tree; therefore it should _not_ be hashed as a leaf
            return BlockStreamManager.HASH_OF_ZERO_BYTES;
        }
        if (hashList.size() == 1) {
            // This value should already have been hashed as a leaf, and therefore should _not_ be re-hashed
            return hashList.getFirst();
        }

        byte[] merkleRootHash = hashList.getLast();
        for (int i = hashList.size() - 2; i >= 0; i--) {
            merkleRootHash = hashInternalNode(hashList.get(i), merkleRootHash);
        }
        return merkleRootHash;
    }

    /**
     * Returns the current intermediate hashing state (pending subtree roots).
     *
     * <p>This can be used to inspect or save the state for later resumption.
     * The returned list is the internal list, not a copy.
     *
     * @return the list of pending subtree root hashes
     */
    public List<Bytes> intermediateHashingState() {
        return hashList.stream().map(Bytes::wrap).toList();
    }

    /**
     * Get the number of leaves added to the tree so far.
     *
     * @return the number of leaves
     */
    public long leafCount() {
        return leafCount;
    }

    /**
     * Saves the current hashing state to a binary file.
     *
     * <p>File format:
     * <ul>
     *   <li>8 bytes: leaf count (long)</li>
     *   <li>4 bytes: hash count (int)</li>
     *   <li>48 bytes × hash count: the pending subtree root hashes</li>
     * </ul>
     *
     * @param filePath the path to the file where the state will be saved
     * @throws Exception if an I/O error occurs
     */
    public void save(Path filePath) throws Exception {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(filePath))) {
            out.writeLong(leafCount);
            out.writeInt(hashList.size());
            for (byte[] hash : hashList) { // all hashes are 48 bytes (SHA-384)
                out.write(hash);
            }
        }
    }

    /**
     * Loads the hashing state from a binary file, replacing any current state.
     *
     * @param filePath the path to the file from which the state will be loaded
     * @throws Exception if an I/O error occurs or the file format is invalid
     */
    public void load(Path filePath) throws Exception {
        try (DataInputStream din = new DataInputStream(Files.newInputStream(filePath))) {
            leafCount = din.readLong();
            int hashCount = din.readInt();
            hashList.clear();
            for (int i = 0; i < hashCount; i++) {
                byte[] hash = new byte[48]; // SHA-384 produces 48-byte hashes
                din.readFully(hash);
                hashList.add(hash);
            }
        }
    }

    /**
     * Hash an internal node by combining the hashes of its two children with the appropriate prefix.
     *
     * @param firstChild the hash of the first child
     * @param secondChild the hash of the second child
     * @return the hash of the internal node
     */
    private byte[] hashInternalNode(final byte[] firstChild, final byte[] secondChild) {
        return BlockImplUtils.hashInternalNode(digest, firstChild, secondChild);
    }
}
