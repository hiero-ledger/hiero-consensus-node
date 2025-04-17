// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;

import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * This is a mechanism to flush data (dirty hashes, dirty leaves, deleted leaves) to disk
 * during reconnect, bypassing virtual node cache and virtual pipeline. This mechanism is
 * used by two components. First, {@link ReconnectNodeRemover}, which deletes leaves from
 * the old (learner) state tree outside the new leaf path range. Second, {@link ReconnectHashListener},
 * which listens for hash updates from virtual hasher.
 *
 * <p>This flusher is thread safe, its methods like {@link #updateHash(long, Hash)},
 * {@link #updateLeaf(VirtualLeafRecord)}, and {@link #deleteLeaf(VirtualLeafRecord)} can
 * be called from multiple threads. However, some of the calling threads may be blocked
 * till the currently accumulated data is flushed to disk.
 *
 * <p>{@link #start(long, long)} must be called in the beginning of flush, and {@link
 * #finish()} must be called in the end.
 *
 * @param <K>
 * @param <V>
 */
public class ReconnectHashLeafFlusher<K extends VirtualKey, V extends VirtualValue> {

    private static final Logger logger = LogManager.getLogger(ReconnectHashLeafFlusher.class);

    // Using 0 as a flag that the path range is not set, since -1,-1 is a valid (empty) range
    private volatile long firstLeafPath = 0;
    private volatile long lastLeafPath = 0;

    private final KeySerializer<K> keySerializer;
    private final ValueSerializer<V> valueSerializer;
    private final VirtualDataSource dataSource;

    private List<VirtualLeafRecord<K, V>> updatedLeaves;
    private List<VirtualLeafRecord<K, V>> deletedLeaves;
    private List<VirtualHashRecord> updatedHashes;

    // Flushes are initiated from onNodeHashed(). While a flush is in progress, other nodes
    // are still hashed in parallel, so it may happen that enough nodes are hashed to
    // start a new flush, while the previous flush is not complete yet. This flag is
    // protection from that
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private final int flushInterval;

    private final VirtualMapStatistics statistics;

    public ReconnectHashLeafFlusher(
            @NonNull final KeySerializer<K> keySerializer,
            @NonNull final ValueSerializer<V> valueSerializer,
            @NonNull final VirtualDataSource dataSource,
            final int flushInterval,
            @NonNull final VirtualMapStatistics statistics) {
        this.keySerializer = Objects.requireNonNull(keySerializer);
        this.valueSerializer = Objects.requireNonNull(valueSerializer);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.flushInterval = flushInterval;
        this.statistics = Objects.requireNonNull(statistics);
    }

    synchronized void start(final long firstLeafPath, final long lastLeafPath) {
        if (firstLeafPath != Path.INVALID_PATH && !(firstLeafPath > 0 && firstLeafPath <= lastLeafPath)) {
            throw new IllegalArgumentException("The first leaf path is invalid. firstLeafPath=" + firstLeafPath
                    + ", lastLeafPath=" + lastLeafPath);
        }
        if (lastLeafPath != Path.INVALID_PATH && lastLeafPath <= 0) {
            throw new IllegalArgumentException(
                    "The last leaf path is invalid. firstLeafPath=" + firstLeafPath + ", lastLeafPath=" + lastLeafPath);
        }
        if ((this.firstLeafPath == 0) && (this.lastLeafPath == 0)) {
            this.firstLeafPath = firstLeafPath;
            this.lastLeafPath = lastLeafPath;
            assert (updatedHashes == null) && (updatedLeaves == null) && (deletedLeaves == null)
                    : "Reconnect must not be started yet";
            updatedHashes = new ArrayList<>();
            updatedLeaves = new ArrayList<>();
            deletedLeaves = new ArrayList<>();
        } else {
            // Allow this method to be called multiple times (e.g. from a node remover and from
            // a hash listener), but only with the same path range
            assert this.firstLeafPath == firstLeafPath;
            assert this.lastLeafPath == lastLeafPath;
        }
    }

    void updateHash(final long path, final Hash hash) {
        assert (updatedHashes != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "updateHash called without start";
        actionAndCheckFlush(() -> updatedHashes.add(new VirtualHashRecord(path, hash)));
    }

    void updateLeaf(final VirtualLeafRecord<K, V> leaf) {
        assert (updatedHashes != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "updateLeaf called without start";
        actionAndCheckFlush(() -> updatedLeaves.add(leaf));
    }

    void deleteLeaf(final VirtualLeafRecord<K, V> leaf) {
        assert (updatedHashes != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "deleteLeaf called without start";
        actionAndCheckFlush(() -> deletedLeaves.add(leaf));
    }

    private void actionAndCheckFlush(final Runnable action) {
        final List<VirtualHashRecord> dirtyHashesToFlush;
        final List<VirtualLeafRecord<K, V>> dirtyLeavesToFlush;
        final List<VirtualLeafRecord<K, V>> deletedLeavesToFlush;
        synchronized (this) {
            action.run();
            if (!isFlushNeeded() || !flushInProgress.compareAndSet(false, true)) {
                return;
            }
            dirtyHashesToFlush = updatedHashes;
            updatedHashes = new ArrayList<>();
            dirtyLeavesToFlush = updatedLeaves;
            updatedLeaves = new ArrayList<>();
            deletedLeavesToFlush = deletedLeaves;
            deletedLeaves = new ArrayList<>();
        }
        // Call flush() outside of the synchronized block to make sure updateHash(), updateLeaf(), and
        // deleteLeaf() aren't blocked on other threads
        flush(dirtyHashesToFlush, dirtyLeavesToFlush, deletedLeavesToFlush);
    }

    private boolean isFlushNeeded() {
        if (flushInterval <= 0) {
            // All data is flushed in finish() only
            return false;
        }
        return (updatedHashes.size() >= flushInterval)
                || (updatedLeaves.size() >= flushInterval)
                || (deletedLeaves.size() >= flushInterval);
    }

    synchronized void finish() {
        assert (updatedHashes != null) && (updatedLeaves != null) && (deletedLeaves != null)
                : "finish called without start";
        final List<VirtualHashRecord> dirtyHashesToFlush = updatedHashes;
        final List<VirtualLeafRecord<K, V>> dirtyLeavesToFlush = updatedLeaves;
        final List<VirtualLeafRecord<K, V>> deletedLeavesToFlush = deletedLeaves;
        updatedHashes = null;
        updatedLeaves = null;
        deletedLeaves = null;
        assert !flushInProgress.get() : "Flush must not be in progress when reconnect is finished";
        flushInProgress.set(true);
        // Nodes / leaves lists may be empty, but a flush is still needed to make sure
        // all stale leaves are removed from the data source
        flush(dirtyHashesToFlush, dirtyLeavesToFlush, deletedLeavesToFlush);
    }

    // Since flushes may take quite some time, this method is called outside synchronized blocks.
    private void flush(
            @NonNull final List<VirtualHashRecord> hashesToFlush,
            @NonNull final List<VirtualLeafRecord<K, V>> leavesToFlush,
            @NonNull final List<VirtualLeafRecord<K, V>> leavesToDelete) {
        assert flushInProgress.get() : "Flush in progress flag must be set";
        try {
            logger.info(
                    VIRTUAL_MERKLE_STATS.getMarker(),
                    "Reconnect flush: {} updated hashes, {} updated leaves, {} deleted leaves",
                    hashesToFlush.size(),
                    leavesToFlush.size(),
                    leavesToDelete.size());
            // flush it down
            final long start = System.currentTimeMillis();
            try {
                dataSource.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashesToFlush.stream(),
                        leavesToFlush.stream().map(r -> r.toBytes(keySerializer, valueSerializer)),
                        leavesToDelete.stream().map(r -> r.toBytes(keySerializer, valueSerializer)),
                        true);
                final long end = System.currentTimeMillis();
                statistics.recordFlush(end - start);
                logger.debug(VIRTUAL_MERKLE_STATS.getMarker(), "Flushed in {} ms", end - start);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            flushInProgress.set(false);
        }
    }
}
