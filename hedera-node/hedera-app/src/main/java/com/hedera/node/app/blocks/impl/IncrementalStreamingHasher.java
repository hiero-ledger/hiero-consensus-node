// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.List;

/**
 * A class that computes a Merkle tree root hash in a streaming fashion. It supports adding leaves one by one and
 * computes the root hash without storing the entire tree in memory. It uses SHA-384 as the hashing algorithm and
 * follows the prefixing scheme for leaves and internal nodes.
 *
 * <p>This is not thread safe, it is assumed use by single thread.</p>
 */
public class IncrementalStreamingHasher {
    /** Prefix byte for hash contents for leaf nodes. */
    private static final byte[] LEAF_PREFIX = new byte[] {0};
    /** Prefix byte for hash contents for internal nodes. */
    private static final byte[] INTERNAL_NODE_PREFIX = new byte[] {2};
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
        this.hashList.addAll(intermediateHashingState);
        this.leafCount = leafCount;
    }

    /**
     * Add a new leaf to the Merkle tree.
     *
     * @param data the data for the new leaf
     */
    public void addLeaf(byte[] data) {
        final long i = leafCount;
        final byte[] e = hashLeaf(data);
        hashList.add(e);
        for (long n = i; (n & 1L) == 1; n >>= 1) {
            final byte[] y = hashList.removeLast();
            final byte[] x = hashList.removeLast();
            hashList.add(hashInternalNode(x, y));
        }
        leafCount++;
    }

    /**
     * Compute the Merkle tree root hash from the current state. This does not modify the internal state, so can be
     * called at any time and more leaves can be added afterward.
     *
     * @return the Merkle tree root hash, or {@code Bytes.EMPTY} if no leaves exist
     */
    public byte[] computeRootHash() {
        if (hashList.isEmpty()) {
            return Bytes.EMPTY.toByteArray();
        }
        if (hashList.size() == 1) {
            return hashList.getFirst();
        }

        byte[] merkleRootHash = hashList.getLast();
        for (int i = hashList.size() - 2; i >= 0; i--) {
            merkleRootHash = hashInternalNode(hashList.get(i), merkleRootHash);
        }
        return merkleRootHash;
    }

    /**
     * Get the current intermediate hashing state. This can be used to save the state and resume hashing later.
     *
     * @return the intermediate hashing state
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
     * Hash a leaf node with the appropriate prefix.
     *
     * @param leafData the data of the leaf
     * @return the hash of the leaf node
     */
    private byte[] hashLeaf(final byte[] leafData) {
        digest.reset();
        digest.update(LEAF_PREFIX);
        return digest.digest(leafData);
    }

    /**
     * Hash an internal node by combining the hashes of its two children with the appropriate prefix.
     *
     * @param firstChild the hash of the first child
     * @param secondChild the hash of the second child
     * @return the hash of the internal node
     */
    private byte[] hashInternalNode(final byte[] firstChild, final byte[] secondChild) {
        digest.reset();
        digest.update(INTERNAL_NODE_PREFIX);
        digest.update(firstChild);
        return digest.digest(secondChild);
    }
}
