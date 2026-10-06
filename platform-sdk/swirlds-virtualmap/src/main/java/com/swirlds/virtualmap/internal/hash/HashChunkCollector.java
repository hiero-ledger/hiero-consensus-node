// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.virtualmap.MerklePathUtils.ROOT_PATH;
import static java.util.Objects.requireNonNull;

import com.swirlds.virtualmap.MerklePathUtils;
import com.swirlds.virtualmap.TaskPerNodeFullRehasher;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// A [TaskPerNodeFullRehasher.Listener] that builds hash chunks from node hashes reported by [TaskPerNodeFullRehasher]
/// and passes complete chunks to a [VirtualHashListener].
///
/// Only hashes that are stored in chunks are collected: hashes of nodes at the last chunk
/// ranks, and hashes of leaves at any rank. Chunks are created on demand, when the first hash
/// in a chunk is reported. When a chunk root node is hashed, all nodes in the chunk sub-tree
/// have been hashed already, so the chunk is complete. It's removed from this collector and
/// passed to [VirtualHashListener#onHashChunkHashed(VirtualHashChunk)]. This way, only chunks
/// that are being hashed at the moment are kept in memory.
///
/// Hashing progress, the percentage of hashed leaves, is logged as well.
public final class HashChunkCollector implements TaskPerNodeFullRehasher.Listener {

    private static final Logger logger = LogManager.getLogger(HashChunkCollector.class);

    // The height of the hash chunks. Hashes of nodes at the last chunk ranks are stored in chunks
    private final int chunkHeight;
    // The listener to pass complete chunks to
    private final VirtualHashListener chunkListener;

    // Chunks that are being hashed, by chunk path; removed from this map when the chunk is complete and passed to the
    // chunk listener
    private final Map<Long, VirtualHashChunk> chunksInProgress = new ConcurrentHashMap<>();

    // The first leaf path in the virtual tree
    private long firstLeafPath;
    // The last leaf path in the virtual tree
    private long lastLeafPath;
    // The number of leaves that make 1% of the total leaves. Used to log hashing progress
    private long onePercentLeavesCount;
    // The number of hashed leaves. Used to log hashing progress
    private final AtomicLong hashedLeavesCount = new AtomicLong();

    /// Creates a new chunk collector.
    ///
    /// @param chunkHeight hash chunk height
    /// @param chunkListener the listener to pass complete chunks to
    public HashChunkCollector(final int chunkHeight, @NonNull final VirtualHashListener chunkListener) {
        if (chunkHeight <= 0) {
            throw new IllegalArgumentException("Wrong chunk height: " + chunkHeight);
        }
        this.chunkHeight = chunkHeight;
        this.chunkListener = requireNonNull(chunkListener);
    }

    /// Must be called before hashing is started.
    ///
    /// @param firstLeafPath the first leaf path in the virtual tree
    /// @param lastLeafPath the last leaf path in the virtual tree
    public void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.onePercentLeavesCount = (lastLeafPath - firstLeafPath) / 100 + 1;
        hashedLeavesCount.set(0);
        chunkListener.onHashingStarted(firstLeafPath, lastLeafPath);
    }

    /// Stores the hash in its chunk, if the hash is a leaf hash or a hash at the last chunk
    /// rank. If the node is a chunk root, its chunk is complete, and it's passed to the chunk
    /// listener. Since the chunk listener may flush chunks, this method may block.
    ///
    /// @param path the node path
    /// @param hash the node hash bytes
    @Override
    public void onHashed(final long path, @NonNull final byte[] hash) {
        assert path <= lastLeafPath
                : "Hashed path must <= to the last leaf path, path = " + path + ", lastLeafPath = " + lastLeafPath;
        assert path >= 0 : "Hashed path must be non-negative, path = " + path;

        final boolean leaf = path >= firstLeafPath;
        final boolean chunkRank = MerklePathUtils.getRank(path) % chunkHeight == 0;

        // Hashes of internal nodes at internal chunk ranks are not stored in chunks. They
        // must not be set to chunks either, as they would overwrite hashes of their left
        // grand children at the last chunk rank. The root hash isn't stored in any chunk
        if ((path != ROOT_PATH) && (leaf || chunkRank)) {
            final VirtualHashChunk chunk = chunksInProgress.computeIfAbsent(
                    VirtualHashChunk.pathToChunkPath(path, chunkHeight), p -> new VirtualHashChunk(p, chunkHeight));
            // Hashes at different ranks in a single chunk may be set in parallel from different
            // hashing threads, but VirtualHashChunk data rank update isn't thread safe
            synchronized (chunk) {
                chunk.setHashBytesAtPath(path, hash);
            }
        }

        // The node is a chunk root. All nodes in its sub-tree are hashed, so the chunk is complete.
        // Leaves have no sub-trees, so there are no chunks at leaf paths
        if (chunkRank && !leaf) {
            final VirtualHashChunk chunk = chunksInProgress.remove(path);
            if (chunk != null) {
                chunkListener.onHashChunkHashed(chunk);
            }
        }

        if (leaf) {
            final long hashed = hashedLeavesCount.incrementAndGet();
            if (hashed % onePercentLeavesCount == 0) {
                logger.info(STARTUP.getMarker(), "Full rehash progress: {}%", hashed / onePercentLeavesCount);
            }
        }
    }

    /// Must be called after hashing is successfully completed.
    ///
    /// @throws IllegalStateException if some chunks are not complete. It would mean these
    ///     chunks are never passed to the chunk listener
    public void onHashingCompleted() {
        if (!chunksInProgress.isEmpty()) {
            throw new IllegalStateException("All chunks must be complete, remaining: " + chunksInProgress.size());
        }
        chunkListener.onHashingCompleted();
    }
}
