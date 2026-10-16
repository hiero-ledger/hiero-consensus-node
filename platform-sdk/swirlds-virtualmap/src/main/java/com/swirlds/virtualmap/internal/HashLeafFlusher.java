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
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

/// This is a mechanism to flush data (dirty hashes, dirty leaves, deleted leaves) to disk
/// outside of the virtual map cache and pipeline.
///
/// This flusher is thread safe, its methods like [#updateHashChunk(VirtualHashChunk)],
/// [#updateLeaf(VirtualLeafBytes)], and [#deleteLeaf(VirtualLeafBytes)] can
/// be called from multiple threads. At most one flush runs at a time. However, some of the calling threads may
/// be blocked till the currently accumulated data is flushed to disk.
///
/// [#init(long, long)] must be called before any producers start appending changes,
/// and [#finish()] must be called in the end, after all producers are done.
///
/// Memory is bounded by backpressure: while a flush is running, producers fill one next batch until
/// it's due for a flush, then block until the running flush completes, so at most about two batches are
/// kept in memory. Blocking uses `synchronized` and `wait()` rather than a `Condition`,
/// so that ForkJoinPool workers don't spawn compensation threads. Flushing must never depend on progress
/// of producers, otherwise blocked producers may deadlock.
///
/// Flushes run synchronously in one of the calling threads, so any of [#updateHashChunk(VirtualHashChunk)],
/// [#updateLeaf(VirtualLeafBytes)], [#deleteLeaf(VirtualLeafBytes)], and [#finish()] may throw
/// an exception, if a flush fails. The exception is thrown to the thread that ran the flush, and data in the
/// failed batch is lost, so the caller must treat it as a failure of the whole job. Producers blocked on the
/// failed flush are released.
///
/// If the job that feeds this flusher fails or times out, it must call [#cancel()]. Producers blocked on
/// backpressure are released, and all subsequent calls throw an [IllegalStateException] with a
/// [CancellationException] as the cause. A flush that is already running isn't interrupted, but no new
/// flushes are started. Cancellation only unblocks and fails calls into this flusher, the caller is still
/// responsible for stopping the producers.
public class HashLeafFlusher {

    private static final Logger logger = LogManager.getLogger(HashLeafFlusher.class);

    // Using 0 as a flag that the path range is not set, since -1,-1 is a valid (empty) range
    private volatile long firstLeafPath = 0;
    private volatile long lastLeafPath = 0;

    private final VirtualDataSource dataSource;

    // The batch that is currently being filled. Null before init() and after finish(). Guarded by this
    private Batch batch;

    // Flushes are initiated from updateHashChunk(), updateLeaf(), and deleteLeaf(). While a flush is in progress, other
    // producers keep adding data in parallel, so it may happen that enough data is added to start a new flush, while
    // the previous flush is not complete yet. This flag is protection from that. Guarded by this
    private boolean flushInProgress = false;

    // Set by cancel(). Once set, all subsequent calls fail. Guarded by this
    private boolean cancelled = false;

    // When producers started blocking on the running flush, or 0 if none are blocked. Guarded by this
    private long backpressureStart = 0;

    // Total time producers were blocked on backpressure, logged in finish(). A long total may indicate
    // a slow disk, and it may make reconnect fail, since a blocked learner stops reading from the teacher,
    // which times out. Guarded by this
    private long backpressureMs = 0;

    private final int hashChunkHeight;

    private final int flushInterval;

    private final VirtualMapStatistics statistics;

    private final Marker logMarker;

    // Passed to the data source with every flush, see VirtualDataSource.saveRecords()
    private final boolean reconnectContext;

    // Data accumulated for a single flush
    private static final class Batch {
        final List<VirtualHashChunk> hashChunks = new ArrayList<>();
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        final List<VirtualLeafBytes> deletedLeaves = new ArrayList<>();
    }

    /// Creates a flusher that logs with the `VIRTUAL_MERKLE_STATS` marker and doesn't flush in the
    /// reconnect context.
    ///
    /// @param dataSource the data source to flush data to
    /// @param flushInterval the flush threshold, see
    ///     [#HashLeafFlusher(VirtualDataSource, int, VirtualMapStatistics, Marker, boolean)]
    /// @param statistics virtual map statistics to record flush durations
    public HashLeafFlusher(
            @NonNull final VirtualDataSource dataSource,
            final int flushInterval,
            @NonNull final VirtualMapStatistics statistics) {
        this(dataSource, flushInterval, statistics, VIRTUAL_MERKLE_STATS.getMarker(), false);
    }

    /// Creates a flusher that logs with the given marker.
    ///
    /// @param dataSource the data source to flush data to
    /// @param flushInterval the flush threshold. A flush is started once any of the following reaches this
    ///     value: the number of hash slots in collected hash chunks (number of chunks times the chunk size),
    ///     the number of updated leaves, or the number of deleted leaves. While a flush is running, the same
    ///     threshold is used for backpressure. If zero or negative, all data is flushed in [#finish()] only,
    ///     and producers never block
    /// @param statistics virtual map statistics to record flush durations
    /// @param logMarker the marker to use for flush logs
    /// @param reconnectContext whether data is flushed in the reconnect context. Passed to the data source
    ///     with every flush, see [VirtualDataSource#saveRecords]. Affects how leaves are deleted, see
    ///     [#deleteLeaf(VirtualLeafBytes)]
    public HashLeafFlusher(
            @NonNull final VirtualDataSource dataSource,
            final int flushInterval,
            @NonNull final VirtualMapStatistics statistics,
            @NonNull final Marker logMarker,
            final boolean reconnectContext) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.hashChunkHeight = this.dataSource.getHashChunkHeight();
        this.flushInterval = flushInterval;
        this.statistics = Objects.requireNonNull(statistics, "statistics must not be null");
        this.logMarker = Objects.requireNonNull(logMarker, "logMarker must not be null");
        this.reconnectContext = reconnectContext;
    }

    /// Initializes this flusher with the leaf path range of the target tree. Must be called once, before any
    /// other methods. The range is passed to the data source with every flush.
    ///
    /// @param firstLeafPath the first leaf path, or [MerklePathUtils#INVALID_PATH] if the tree is empty
    /// @param lastLeafPath the last leaf path, or [MerklePathUtils#INVALID_PATH] if the tree is empty
    /// @throws IllegalArgumentException if the path range is invalid, or if this flusher is already initialized
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

        batch = new Batch();

        logger.info(
                logMarker,
                "Hash leaf flusher initialized with firstLeafPath={}, lastLeafPath={}",
                firstLeafPath,
                lastLeafPath);
    }

    /// Adds an updated hash chunk to the current batch. May be called from multiple threads.
    ///
    /// If the batch is due for a flush, and no other flush is running, the batch is flushed in the
    /// current thread before this method returns. If another flush is running and the batch is already due
    /// for a flush, this method blocks till the running flush is complete.
    ///
    /// @param chunk the hash chunk to save
    /// @throws RuntimeException if a flush run in the current thread fails, see class docs
    /// @throws IllegalStateException if this flusher is cancelled, or if the current thread is interrupted while
    ///     waiting for or running a flush. The interrupted flag is left set
    public void updateHashChunk(@NonNull final VirtualHashChunk chunk) {
        addToBatchAndCheckFlush(batch -> batch.hashChunks.add(chunk));
    }

    /// Adds an updated leaf to the current batch. May be called from multiple threads. Flushes and blocks
    /// the same way as [#updateHashChunk(VirtualHashChunk)].
    ///
    /// @param leaf the leaf to save
    /// @throws RuntimeException if a flush run in the current thread fails, see class docs
    /// @throws IllegalStateException if this flusher is cancelled, or if the current thread is interrupted while
    ///     waiting for or running a flush. The interrupted flag is left set
    public void updateLeaf(final VirtualLeafBytes<?> leaf) {
        addToBatchAndCheckFlush(batch -> batch.leaves.add(leaf));
    }

    /// Adds a leaf to delete to the current batch. May be called from multiple threads. Flushes and blocks
    /// the same way as [#updateHashChunk(VirtualHashChunk)].
    ///
    /// In the reconnect context, the leaf is a candidate for removal only. During the flush, its key is
    /// removed from the data source only if the key is still mapped to the leaf path, so a key that has
    /// been moved to a different path isn't lost. Otherwise, the key is removed unconditionally.
    ///
    /// @param leaf the leaf to delete
    /// @throws RuntimeException if a flush run in the current thread fails, see class docs
    /// @throws IllegalStateException if this flusher is cancelled, or if the current thread is interrupted while
    ///     waiting for or running a flush. The interrupted flag is left set
    public void deleteLeaf(final VirtualLeafBytes<?> leaf) {
        addToBatchAndCheckFlush(batch -> batch.deletedLeaves.add(leaf));
    }

    /// Cancels this flusher. Threads blocked waiting for a running flush are released, and all subsequent
    /// calls to [#updateHashChunk(VirtualHashChunk)], [#updateLeaf(VirtualLeafBytes)],
    /// [#deleteLeaf(VirtualLeafBytes)], and [#finish()] throw an [IllegalStateException] with a
    /// [CancellationException] as the cause. A flush that is already running isn't interrupted, but no new
    /// flushes are started. May be called multiple times, from any thread, at any moment. Never waits for a
    /// running flush.
    ///
    /// This method only affects calls into this flusher. It doesn't stop producers themselves, this is the
    /// caller's responsibility.
    public synchronized void cancel() {
        cancelled = true;
        notifyAll();
    }

    /// Flushes all remaining data in the current thread. The final flush is run even if there is no data,
    /// so the data source is always updated with the leaf path range from [#init(long, long)]. Must be
    /// called once, after all calls to [#updateHashChunk(VirtualHashChunk)], [#updateLeaf(VirtualLeafBytes)],
    /// and [#deleteLeaf(VirtualLeafBytes)] have returned. Since flushes run synchronously in the calling
    /// threads, no other flush is running at this point, so this method doesn't wait for one.
    ///
    /// @throws RuntimeException if the final flush fails
    /// @throws IllegalStateException if this flusher is cancelled, if this flusher is not initialized or already
    ///     finished, or if the current thread is interrupted while running the final flush. The interrupted flag
    ///     is left set
    public void finish() {
        final Batch last;
        synchronized (this) {
            if (batch == null) {
                throw new IllegalStateException("Flusher is not initialized or already finished");
            }
            // Log before throwIfCancelled(), so the total is logged even if this flusher is cancelled
            logger.info(logMarker, "Producers were blocked on backpressure for {} ms in total", backpressureMs);
            throwIfCancelled();
            assert !flushInProgress : "finish() must be called after all producers are done";
            last = batch;
            batch = null;
        }
        // Nodes / leaves lists may be empty, but a flush is still needed to make sure
        // all stale leaves are removed from the data source. The flush is run outside the
        // synchronized block, so cancel() isn't blocked till the final flush is complete
        flush(last);
    }

    private void addToBatchAndCheckFlush(final Consumer<Batch> batchConsumer) {
        assert batch != null : "Flusher is not initialized or already finished";
        final Batch toFlush;
        synchronized (this) {
            waitIfBackpressured();
            throwIfCancelled();
            batchConsumer.accept(batch);
            // If another flush is running, the batch is left in place even if it's due, since at most one flush
            // may run at a time. Once the running flush completes, producers blocked in waitIfBackpressured()
            // are released, and the first of them to add data flushes the batch. flushInProgress is checked
            // here rather than in isFlushNeeded(), since waitIfBackpressured() needs the opposite: a due batch
            // while a flush is running
            if (flushInProgress || !isFlushNeeded()) {
                return;
            }
            toFlush = batch;
            batch = new Batch();
            flushInProgress = true;
        }
        // Flush outside of the synchronized block to make sure updateHashChunk(), updateLeaf(), and
        // deleteLeaf() aren't blocked for the duration of the flush, unless the next batch is also due
        try {
            flush(toFlush);
        } finally {
            // Release producers blocked on backpressure, even if the flush failed
            synchronized (this) {
                flushInProgress = false;
                notifyAll();
            }
        }
    }

    // Must be called with the lock held. CancellationException is only the cause: if thrown directly, it would
    // make futures and fork join tasks of the callers look cancelled rather than failed
    private void throwIfCancelled() {
        if (cancelled) {
            throw new IllegalStateException("Flusher is cancelled", new CancellationException("Flusher is cancelled"));
        }
    }

    // Backpressure: if the current batch is already due for a flush while another flush is running,
    // waits till the running flush is complete, or returns if no flush in progress and current batch is not full.
    // Must be called with the lock held
    private void waitIfBackpressured() {
        while (!cancelled && flushInProgress && isFlushNeeded()) {
            if (backpressureStart == 0) {
                backpressureStart = System.currentTimeMillis();
            }
            try {
                wait();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for a flush", e);
            }
        }
        // The first producer released from a stall adds its duration, others see 0 and skip it
        if (backpressureStart != 0) {
            backpressureMs += System.currentTimeMillis() - backpressureStart;
            backpressureStart = 0;
        }
    }

    // Must be called with the lock held
    private boolean isFlushNeeded() {
        // If flushInterval isn't positive, all data is flushed in finish() only
        return flushInterval > 0
                && ((batch.hashChunks.size() * VirtualHashChunk.getChunkSize(hashChunkHeight) >= flushInterval)
                        || (batch.leaves.size() >= flushInterval)
                        || (batch.deletedLeaves.size() >= flushInterval));
    }

    // Since flushes may take quite some time, this method is always called outside synchronized blocks
    private void flush(@NonNull final Batch toFlush) {
        throwIfInterrupted();
        logger.info(
                logMarker,
                "Flush: {} updated hash chunks, {} updated leaves, {} deleted leaves",
                toFlush.hashChunks.size(),
                toFlush.leaves.size(),
                toFlush.deletedLeaves.size());
        // flush it down
        final long start = System.currentTimeMillis();
        try {
            dataSource.saveRecords(
                    firstLeafPath,
                    lastLeafPath,
                    toFlush.hashChunks.stream(),
                    toFlush.leaves.stream(),
                    toFlush.deletedLeaves.stream(),
                    reconnectContext);
            // If interrupted, saveRecords() restores the interrupted flag and returns normally,
            // possibly before all data is written. This must not be treated as a success
            throwIfInterrupted();
            final long end = System.currentTimeMillis();
            statistics.recordFlush(end - start);
            logger.debug(logMarker, "Flushed in {} ms", end - start);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Fails the flush if the current thread is interrupted. The interrupted flag is left set
    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while flushing");
        }
    }
}
