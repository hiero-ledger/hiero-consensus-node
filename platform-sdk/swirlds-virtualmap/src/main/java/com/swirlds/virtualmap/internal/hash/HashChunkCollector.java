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
/// Chunks are created on demand, when the first hash
/// in a chunk is reported. When a chunk root node is hashed, all nodes in the chunk sub-tree
/// have been hashed already, so the chunk is complete. It's removed from this collector and
/// passed to [VirtualHashListener#onHashChunkHashed(VirtualHashChunk)]. This way, only chunks
/// that are being hashed at the moment are kept in memory.
///
/// When the root node is hashed, all chunks are complete, and [VirtualHashListener#onHashingCompleted()]
/// is called in the same thread. Since [TaskPerNodeFullRehasher] returns the root hash only after the
/// root node listener call returns, the chunk listener is completed, e.g. all chunks are flushed, by
/// the time the root hash is returned.
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
    private final long firstLeafPath;
    // The last leaf path in the virtual tree
    private final long lastLeafPath;
    // The number of leaves that make 1% of the total leaves. Used to log hashing progress
    private final long onePercentLeavesCount;
    // The number of hashed leaves. Used to log hashing progress
    private final AtomicLong hashedLeavesCount = new AtomicLong();

    /// Creates a chunk collector for a single hashing run of the given leaf path range, and
    /// notifies the chunk listener that hashing is started. A collector must not be reused for
    /// multiple hashing runs.
    ///
    /// @param chunkHeight hash chunk height
    /// @param firstLeafPath the first leaf path in the virtual tree
    /// @param lastLeafPath the last leaf path in the virtual tree
    /// @param chunkListener the listener to pass complete chunks to
    public HashChunkCollector(
            final int chunkHeight,
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final VirtualHashListener chunkListener) {
        if (chunkHeight <= 0) {
            throw new IllegalArgumentException("Wrong chunk height: " + chunkHeight);
        }
        this.chunkHeight = chunkHeight;
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.onePercentLeavesCount = (lastLeafPath - firstLeafPath) / 100 + 1;
        this.chunkListener = requireNonNull(chunkListener);
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
        // True if the node is at a chunk boundary rank (0, chunkHeight, 2 * chunkHeight, ...). Such a node
        // plays two roles: it's at the last rank of the chunk above it, so its hash is stored there, and, if
        // it's not a leaf, it's the root of the chunk below it. The root node only plays the second role, as
        // there is no chunk above it
        final boolean chunkRank = MerklePathUtils.getRank(path) % chunkHeight == 0;

        // Store the hash in its chunk, if the chunk stores it. A chunk stores hashes of nodes at its last
        // rank, which is a chunk rank, and hashes of leaves at any rank. Hashes of internal nodes at other
        // ranks are not stored. They must not be set to chunks either, as they would overwrite hashes of
        // their left grand children at the last chunk rank. The root hash isn't stored in any chunk, as
        // there is no chunk above the root
        if ((path != ROOT_PATH) && (leaf || chunkRank)) {
            final VirtualHashChunk chunk = chunksInProgress.computeIfAbsent(
                    VirtualHashChunk.pathToChunkPath(path, chunkHeight), p -> new VirtualHashChunk(p, chunkHeight));
            // Hashes at different ranks in a single chunk may be set in parallel from different
            // hashing threads, but VirtualHashChunk data rank update isn't thread safe
            synchronized (chunk) {
                chunk.setHashBytesAtPath(path, hash);
            }
        }

        // The node is a chunk root: an internal node at a chunk rank. A node is hashed only after
        // its children, so all nodes in its sub-tree are hashed, and all hashes stored in its chunk
        // are set. The chunk is complete. Leaves at chunk ranks are not chunk roots: they have no
        // sub-trees, hence no chunks of their own. Their hashes are stored in the chunk above
        if (chunkRank && !leaf) {
            final VirtualHashChunk chunk = chunksInProgress.remove(path);
            if (chunk != null) {
                chunkListener.onHashChunkHashed(chunk);
            }

            if (path == ROOT_PATH) {
                // The root chunk is complete, so all chunks are complete. The chunk listener must be
                // completed here, before the root hash is returned from the rehasher, see class javadoc
                if (!chunksInProgress.isEmpty()) {
                    throw new IllegalStateException(
                            "All chunks must be complete, remaining: " + chunksInProgress.size());
                }
                chunkListener.onHashingCompleted();
            }
        }

        if (leaf) {
            final long hashed = hashedLeavesCount.incrementAndGet();
            if (hashed % onePercentLeavesCount == 0) {
                logger.info(STARTUP.getMarker(), "Full rehash progress: {}%", hashed / onePercentLeavesCount);
            }
        }
    }
}
