// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * A naive implementation of {@link StreamingTreeHasher} that computes the root hash of a perfect binary Merkle tree of
 * {@link ByteBuffer} leaves. Used to test the correctness of more efficient implementations.
 */
public class NaiveStreamingTreeHasher implements StreamingTreeHasher {
    private static final Bytes EMPTY_HASH_BYTES = hashLeaf(BlockStreamManager.ZERO_BLOCK_HASH);
    private static final byte[] EMPTY_HASH = EMPTY_HASH_BYTES.toByteArray();

    private final List<byte[]> leafHashes = new ArrayList<>();
    private boolean rootHashRequested = false;

    /**
     * Computes the root hash of a perfect binary Merkle tree of {@link ByteBuffer} leaves using a naive algorithm.
     * <p>
     * This method does <b>not</b> prefix leaf values with {@link StreamingTreeHasher#LEAF_PREFIX} before hashing,
     * assuming this was done prior.
     * @param leafHashes the leaf hashes of the tree
     * @return the root hash of the tree
     */
    public static Bytes computeRootHash(@NonNull final List<byte[]> leafHashes) {
        final var hasher = new NaiveStreamingTreeHasher();
        for (final var hash : leafHashes) {
            hasher.addLeaf(ByteBuffer.wrap(hash));
        }
        return hasher.rootHash().join();
    }

    @Override
    public void addLeaf(@NonNull final ByteBuffer hash) {
        if (rootHashRequested) {
            throw new IllegalStateException("Root hash already requested");
        }
        if (hash.remaining() < HASH_LENGTH) {
            throw new IllegalArgumentException("Buffer has less than " + HASH_LENGTH + " bytes remaining");
        }
        final var bytes = new byte[HASH_LENGTH];
        hash.get(bytes);
        leafHashes.add(bytes);
    }

    @Override
    public CompletableFuture<Bytes> rootHash() {
        rootHashRequested = true;
        if (leafHashes.isEmpty()) {
            // Even though we have no leaves in this tree at all, and typically hash a value before adding it as a leaf,
            // the empty hash value here has already been hashed as a leaf, and therefore doesn't need to be hashed
            // again
            return CompletableFuture.completedFuture(EMPTY_HASH_BYTES);
        }
        Queue<byte[]> hashes = new LinkedList<>(leafHashes);
        final int n = hashes.size();
        if ((n & (n - 1)) != 0) {
            final var paddedN = Integer.highestOneBit(n) << 1;
            while (hashes.size() < paddedN) {
                hashes.add(EMPTY_HASH);
            }
        }
        while (hashes.size() > 1) {
            final Queue<byte[]> newLeafHashes = new LinkedList<>();
            while (!hashes.isEmpty()) {
                final byte[] left = hashes.poll();
                final byte[] right = hashes.poll();
                final byte[] hashed = BlockImplUtils.hashInternalNode(left, requireNonNull(right));
                newLeafHashes.add(hashed);
            }
            hashes = newLeafHashes;
        }
        return CompletableFuture.completedFuture(Bytes.wrap(requireNonNull(hashes.poll())));
    }
}
