// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;

import com.swirlds.virtualmap.MerklePathUtils;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

/**
 * This is a mechanism to flush data (dirty hashes, dirty leaves, deleted leaves) to disk
 * outside of the virtual map cache and pipeline.
 *
 * <p>This flusher is thread safe, its methods like {@link #updateHashChunk(VirtualHashChunk)},
 * {@link #updateLeaf(VirtualLeafBytes)}, and {@link #deleteLeaf(VirtualLeafBytes)} can
 * be called from multiple threads. At most one flush runs at a time. However, some of the calling threads may
 * be blocked till the currently accumulated data is flushed to disk.
 *
 * <p>{@link #init(long, long)} must be called in the beginning of flush, and {@link
 * #finish()} must be called in the end.
 *
 */
public class HashLeafFlusher {

    private static final Logger logger = LogManager.getLogger(HashLeafFlusher.class);

    // Using 0 as a flag that the path range is not set, since -1,-1 is a valid (empty) range
    private volatile long firstLeafPath = 0;
    private volatile long lastLeafPath = 0;

    private final VirtualDataSource dataSource;

    private List<VirtualLeafBytes> updatedLeaves;
    private List<VirtualLeafBytes> deletedLeaves;
    private List<VirtualHashChunk> updatedHashChunks;

    // Flushes are initiated from updateHashChunk(), updateLeaf(), and deleteLeaf(). While a flush is in progress, other
    // nodes
    // are still hashed in parallel, so it may happen that enough nodes are hashed to
    // start a new flush, while the previous flush is not complete yet. This flag is
    // protection from that
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private final int hashChunkHeight;

    private final int flushInterval;

    private final VirtualMapStatistics statistics;

    private final Marker logMarker;

    /**
     * Creates a flusher that logs with the {@code VIRTUAL_MERKLE_STATS} marker.
     */
    public HashLeafFlusher(
            @NonNull final VirtualDataSource dataSource,
            final int flushInterval,
            @NonNull final VirtualMapStatistics statistics) {
        this(dataSource, flushInterval, statistics, VIRTUAL_MERKLE_STATS.getMarker());
    }

    /**
     * Creates a flusher that logs with the given marker.
     */
    public HashLeafFlusher(
            @NonNull final VirtualDataSource dataSource,
            final int flushInterval,
            @NonNull final VirtualMapStatistics statistics,
            @NonNull final Marker logMarker) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.hashChunkHeight = this.dataSource.getHashChunkHeight();
        this.flushInterval = flushInterval;
        this.statistics = Objects.requireNonNull(statistics, "statistics must not be null");
        this.logMarker = Objects.requireNonNull(logMarker, "logMarker must not be null");
    }

    public synchronized void init(final long firstLeafPath, final long lastLeafPath) {
        if (firstLeafPath != MerklePathUtils.INVALID_PATH && !(firstLeafPath > 0 && firstLeafPath <= lastLeafPath)) {
            throw new IllegalArgumentException("The first leaf path is invalid. firstLeafPath=" + firstLeafPath
                    + ", lastLeafPath=" + lastLeafPath);
        }
        if (lastLeafPath != MerklePathUtils.INVALID_PATH && lastLeafPath <= 0) {
            throw new IllegalArgumentException(
                    "The last leaf path is invalid. firstLeafPath=" + firstLeafPath + ", lastLeafPath=" + lastLeafPath);
        }
        if ((this.firstLeafPath != 0) && (this.lastLeafPath != 0)) {
            throw new IllegalArgumentException("Flusher was already initialized. firstLeafPath=" + this.firstLeafPath
                    + ", lastLeafPath=" + this.lastLeafPath);
        }

        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;

        updatedHashChunks = new ArrayList<>();
        updatedLeaves = new ArrayList<>();
        deletedLeaves = new ArrayList<>();

        logger.info(
                logMarker,
                "Hash leaf flusher initialized with firstLeafPath={}, lastLeafPath={}",
                firstLeafPath,
                lastLeafPath);
    }

    public void updateHashChunk(@NonNull final VirtualHashChunk chunk) {
        assert (updatedHashChunks != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "updateHash called without init";
        actionAndCheckFlush(() -> updatedHashChunks.add(chunk));
    }

    public void updateLeaf(final VirtualLeafBytes<?> leaf) {
        assert (updatedHashChunks != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "updateLeaf called without init";
        actionAndCheckFlush(() -> updatedLeaves.add(leaf));
    }

    public void deleteLeaf(final VirtualLeafBytes<?> leaf) {
        assert (updatedHashChunks != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "deleteLeaf called without init";
        actionAndCheckFlush(() -> deletedLeaves.add(leaf));
    }

    private void actionAndCheckFlush(final Runnable action) {
        final List<VirtualHashChunk> dirtyHashChunksToFlush;
        final List<VirtualLeafBytes> dirtyLeavesToFlush;
        final List<VirtualLeafBytes> deletedLeavesToFlush;
        synchronized (this) {
            action.run();
            if (!isFlushNeeded() || !flushInProgress.compareAndSet(false, true)) {
                return;
            }
            dirtyHashChunksToFlush = updatedHashChunks;
            updatedHashChunks = new ArrayList<>();
            dirtyLeavesToFlush = updatedLeaves;
            updatedLeaves = new ArrayList<>();
            deletedLeavesToFlush = deletedLeaves;
            deletedLeaves = new ArrayList<>();
        }
        // Call flush() outside of the synchronized block to make sure updateHash(), updateLeaf(), and
        // deleteLeaf() aren't blocked on other threads
        flush(dirtyHashChunksToFlush, dirtyLeavesToFlush, deletedLeavesToFlush);
    }

    private boolean isFlushNeeded() {
        if (flushInterval <= 0) {
            // All data is flushed in finish() only
            return false;
        }
        return (updatedHashChunks.size() * VirtualHashChunk.getChunkSize(hashChunkHeight) >= flushInterval)
                || (updatedLeaves.size() >= flushInterval)
                || (deletedLeaves.size() >= flushInterval);
    }

    public synchronized void finish() {
        assert (updatedHashChunks != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "finish called without init";
        final List<VirtualHashChunk> dirtyHashChunksToFlush = updatedHashChunks;
        final List<VirtualLeafBytes> dirtyLeavesToFlush = updatedLeaves;
        final List<VirtualLeafBytes> deletedLeavesToFlush = deletedLeaves;
        updatedHashChunks = null;
        updatedLeaves = null;
        deletedLeaves = null;
        assert !flushInProgress.get() : "Flush must not be in progress when reconnect is finished";
        flushInProgress.set(true);
        // Nodes / leaves lists may be empty, but a flush is still needed to make sure
        // all stale leaves are removed from the data source
        flush(dirtyHashChunksToFlush, dirtyLeavesToFlush, deletedLeavesToFlush);
    }

    // Since flushes may take quite some time, this method is called outside synchronized blocks.
    private void flush(
            @NonNull final List<VirtualHashChunk> hashChunksToFlush,
            @NonNull final List<VirtualLeafBytes> leavesToFlush,
            @NonNull final List<VirtualLeafBytes> leavesToDelete) {
        assert flushInProgress.get() : "Flush in progress flag must be set";
        try {
            throwIfInterrupted();
            logger.info(
                    logMarker,
                    "Flush: {} updated hash chunks, {} updated leaves, {} deleted leaves",
                    hashChunksToFlush.size(),
                    leavesToFlush.size(),
                    leavesToDelete.size());
            // flush it down
            final long start = System.currentTimeMillis();
            try {
                dataSource.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashChunksToFlush.stream(),
                        leavesToFlush.stream(),
                        leavesToDelete.stream(),
                        true);
                // If interrupted, saveRecords() restores the interrupted flag and returns normally,
                // possibly before all data is written. This must not be treated as a success
                throwIfInterrupted();
                final long end = System.currentTimeMillis();
                statistics.recordFlush(end - start);
                logger.debug(logMarker, "Flushed in {} ms", end - start);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            flushInProgress.set(false);
        }
    }

    // Fails the flush if the current thread is interrupted. The interrupted flag is left set
    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while flushing hashes and leaves");
        }
    }
}
