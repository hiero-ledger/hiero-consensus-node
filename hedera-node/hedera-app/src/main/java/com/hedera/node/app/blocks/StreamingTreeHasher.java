// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.hiero.base.crypto.DigestType;

/**
 * Defines a streaming hash computation for a perfect binary Merkle tree of {@link Bytes} leaves; where the leaves
 * given before calling {@link #rootHash()} are right-padded with empty leaves as needed to ensure the final tree is
 * a perfect binary tree.
 */
public interface StreamingTreeHasher {
    int HASH_LENGTH = DigestType.SHA_384.digestLength();
    byte[] LEAF_PREFIX = {0x0};
    byte[] SINGLE_CHILD_INTERNAL_NODE_PREFIX = {0x1};
    byte[] INTERNAL_NODE_PREFIX = {0x2};
    Bytes INTERNAL_NODE_PREFIX_BYTES = Bytes.wrap(INTERNAL_NODE_PREFIX);

    /**
     * Describes the status of the tree hash computation.
     * @param numLeaves the number of leaves added to the tree
     * @param rightmostHashes the rightmost hashes of the tree at each depth
     */
    record Status(int numLeaves, @NonNull List<Bytes> rightmostHashes) {
        public static Status EMPTY = new Status(0, List.of());

        public boolean isEmpty() {
            return numLeaves == 0;
        }
    }

    /**
     * Adds a leaf hash to the implicit tree of items from the given buffer. The buffer's new position
     * will be the current position plus {@link #HASH_LENGTH}.
     * <p>
     * <b>Important:</b> all implementations of this method currently assume that the given leaf bytes have
     * already been prefixed with {@link StreamingTreeHasher#LEAF_PREFIX} prior to hashing.
     *
     * @param hash the leaf hash to add
     * @throws IllegalStateException if the root hash has already been requested
     * @throws IllegalArgumentException if the buffer does not have at least {@link #HASH_LENGTH} bytes remaining
     */
    void addLeaf(@NonNull ByteBuffer hash);

    /**
     * Returns a future that completes with the root hash of the tree of items. Once called, this hasher will not accept
     * any more leaf items.
     * <p>
     * <b>Important:</b> Each implementation of this method <b>MUST</b> prefix all single-child internal
     * nodes with {@link StreamingTreeHasher#SINGLE_CHILD_INTERNAL_NODE_PREFIX}, and any dual-child internal nodes
     * with {@link StreamingTreeHasher#INTERNAL_NODE_PREFIX}, as part of any combined node's hash computation.
     *
     * @return a future that completes with the root hash of the tree of items
     */
    CompletableFuture<Bytes> rootHash();

    /**
     * If supported, blocks until this hasher can give a deterministic summary of the status of the
     * tree hash computation.
     * @return the status of the tree hash computation
     * @throws UnsupportedOperationException if the implementation does not support status reporting
     */
    default Status status() {
        throw new UnsupportedOperationException();
    }
}
