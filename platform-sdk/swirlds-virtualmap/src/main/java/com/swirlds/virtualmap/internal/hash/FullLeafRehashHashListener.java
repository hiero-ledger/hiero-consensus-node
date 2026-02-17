// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static java.util.Objects.requireNonNull;

import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;

/**
 * A {@link VirtualHashListener} that is used during a full leaf rehash of a {@link VirtualMap}.
 *
 * <p>This listener collects hashed leaf records and internal node hashes produced by the {@link VirtualHasher}
 * and flushes them to the underlying {@link VirtualDataSource}.
 *
 * <p>To avoid keeping all hashes in memory during a full rehash (which could involve billions of leaves),
 * this listener flushes data in batches once a threshold ({@code DEFAULT_FLUSH_INTERVAL}) is reached.
 * It also ensures a final flush is performed when hashing is completed.
 */
public class FullLeafRehashHashListener implements VirtualHashListener {

    private static final int DEFAULT_FLUSH_INTERVAL = 500000;

    private static final Logger logger = LogManager.getLogger(FullLeafRehashHashListener.class);

    private final VirtualDataSource dataSource;
    private final long firstLeafPath;
    private final long lastLeafPath;
    private List<VirtualLeafBytes> leaves;
    private List<VirtualHashRecord> hashes;

    // Flushes are initiated from onNodeHashed(). While a flush is in progress, other nodes
    // are still hashed in parallel, so it may happen that enough nodes are hashed to
    // start a new flush, while the previous flush is not complete yet. This flag is
    // protection from that
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private final VirtualMapStatistics statistics;

    /**
     * Create a new {@link FullLeafRehashHashListener}.
     *
     * @param firstLeafPath
     * 		The first leaf path in the range to rehash. Must be a valid path.
     * @param lastLeafPath
     * 		The last leaf path in the range to rehash. Must be a valid path.
     * @param dataSource
     * 		The data source where new hashes and leaf records will be saved. Cannot be null.
     * @param statistics
     *      Statistics object to record flush latency. Cannot be null.
     */
    public FullLeafRehashHashListener(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final VirtualDataSource dataSource,
            @NonNull final VirtualMapStatistics statistics) {

        if (firstLeafPath != Path.INVALID_PATH && !(firstLeafPath > 0 && firstLeafPath <= lastLeafPath)) {
            throw new IllegalArgumentException("The first leaf path is invalid. firstLeafPath=" + firstLeafPath
                    + ", lastLeafPath=" + lastLeafPath);
        }

        if (lastLeafPath != Path.INVALID_PATH && lastLeafPath <= 0) {
            throw new IllegalArgumentException(
                    "The last leaf path is invalid. firstLeafPath=" + firstLeafPath + ", lastLeafPath=" + lastLeafPath);
        }

        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.dataSource = requireNonNull(dataSource);
        this.statistics = requireNonNull(statistics);
    }

    @Override
    public synchronized void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {
        assert (hashes == null) && (leaves == null) : "Hashing must not be started yet";
        hashes = new ArrayList<>();
        leaves = new ArrayList<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNodeHashed(final long path, final Hash hash) {
        assert hashes != null && leaves != null : "onNodeHashed called without onHashingStarted";
        final List<VirtualHashRecord> dirtyHashesToFlush;
        final List<VirtualLeafBytes> dirtyLeavesToFlush;
        synchronized (this) {
            hashes.add(new VirtualHashRecord(path, hash));
            if ((hashes.size() >= DEFAULT_FLUSH_INTERVAL) && flushInProgress.compareAndSet(false, true)) {
                dirtyHashesToFlush = hashes;
                hashes = new ArrayList<>();
                dirtyLeavesToFlush = leaves;
                leaves = new ArrayList<>();
            } else {
                dirtyHashesToFlush = null;
                dirtyLeavesToFlush = null;
            }
        }
        if ((dirtyHashesToFlush != null) && (dirtyLeavesToFlush != null)) {
            flush(dirtyHashesToFlush, dirtyLeavesToFlush);
        }
    }

    @Override
    public synchronized void onLeafHashed(final VirtualLeafBytes<?> leaf) {
        leaves.add(leaf);
    }

    @Override
    public void onHashingCompleted() {
        final List<VirtualHashRecord> finalNodesToFlush;
        final List<VirtualLeafBytes> finalLeavesToFlush;
        synchronized (this) {
            finalNodesToFlush = hashes;
            hashes = null;
            finalLeavesToFlush = leaves;
            leaves = null;
        }
        assert !flushInProgress.get() : "Flush must not be in progress when hashing is complete";
        flushInProgress.set(true);
        // Nodes / leaves lists may be empty, but a flush is still needed to make sure
        // all stale leaves are removed from the data source
        flush(finalNodesToFlush, finalLeavesToFlush);
    }

    // Since flushes may take quite some time, this method is called outside synchronized blocks,
    // otherwise all hashing tasks would be blocked on listener calls until flush is completed.
    private void flush(
            @NonNull final List<VirtualHashRecord> hashesToFlush, @NonNull final List<VirtualLeafBytes> leavesToFlush) {
        assert flushInProgress.get() : "Flush in progress flag must be set";
        try {
            logger.debug(
                    VIRTUAL_MERKLE_STATS.getMarker(),
                    "Flushing {} hashes and {} leaves",
                    hashesToFlush.size(),
                    leavesToFlush.size());
            // flush it down
            final long start = System.currentTimeMillis();
            try {
                dataSource.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashesToFlush.stream(),
                        leavesToFlush.stream(),
                        Stream.empty(),
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
