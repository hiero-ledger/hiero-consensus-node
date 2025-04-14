// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static java.util.Objects.requireNonNull;

import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import com.swirlds.virtualmap.internal.reconnect.ReconnectHashListener;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;

/**
 * The hashing algorithm in the {@link com.swirlds.virtualmap.internal.hash.VirtualHasher} is setup to
 * hash enormous trees in breadth-first order. As the hasher hashes, it notifies this listener which then stores
 * up the changes into different sorted lists.
 * Then, when the "batch" is completed, it flushes the data in the proper order to the data source. This process
 * completely bypasses the {@link com.swirlds.virtualmap.internal.cache.VirtualNodeCache} and the
 * {@link com.swirlds.virtualmap.internal.pipeline.VirtualPipeline}, which is essential for performance and memory
 * reasons, since during reconnect we may need to process the entire data set, which is too large to fit in memory.
 * <p>
 * Three things are required for this listener to work: the {@code firstLeafPath}, the {@code lastLeafPath}, and
 * the {@link VirtualDataSource}.
 * <p>
 * A tree is broken up into "ranks" where "rank 0" is the on that contains root, "rank 1" is the one that contains
 * the left and right children of root, "rank 2" has the children of the nodes in "rank 1", and so forth. The higher
 * the rank, the deeper in the tree the rank lives.
 * <p>
 * A "batch" is a portion of the tree that is independently hashed. The batch will always be processed from the
 * deepest rank (the leaves) to the lowest rank (nearest the top). When we flush, we flush in the opposite order
 * from the closest to the top of the tree to the deepest rank. Each rank is processed in ascending path order.
 * So we store each rank as a separate array and then stream them out in the proper order to disk.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public abstract class AbstractHashListener<K extends VirtualKey, V extends VirtualValue>
        implements VirtualHashListener<K, V> {

    private static final Logger logger = LogManager.getLogger(AbstractHashListener.class);

    private final KeySerializer<K> keySerializer;
    private final ValueSerializer<V> valueSerializer;
    private final VirtualDataSource dataSource;
    private final long firstLeafPath;
    private final long lastLeafPath;
    private List<VirtualLeafRecord<K, V>> leaves;
    private List<VirtualHashRecord> hashes;

    // Flushes are initiated from onNodeHashed(). While a flush is in progress, other nodes
    // are still hashed in parallel, so it may happen that enough nodes are hashed to
    // start a new flush, while the previous flush is not complete yet. This flag is
    // protection from that
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    private int flushInterval = 0;

    private final VirtualMapStatistics statistics;

    /**
     * Create a new {@link ReconnectHashListener}.
     *
     * @param firstLeafPath
     * 		The first leaf path. Must be a valid path.
     * @param lastLeafPath
     * 		The last leaf path. Must be a valid path.
     * @param keySerializer
     *      Virtual key serializer. Cannot be null
     * @param valueSerializer
     *      Virtual value serializer. Cannot be null
     * @param dataSource
     * 		The data source. Cannot be null.
     * @param flushInterval
     *      The number of nodes to hash before they are flushed to disk.
     * @param statistics
     *      Virtual map stats. Cannot be null.
     */
    protected AbstractHashListener(
            final long firstLeafPath,
            final long lastLeafPath,
            final KeySerializer<K> keySerializer,
            final ValueSerializer<V> valueSerializer,
            @NonNull final VirtualDataSource dataSource,
            final int flushInterval,
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
        this.keySerializer = requireNonNull(keySerializer);
        this.valueSerializer = requireNonNull(valueSerializer);
        this.dataSource = requireNonNull(dataSource);
        this.flushInterval = flushInterval;
        this.statistics = requireNonNull(statistics);
    }

    @Override
    public synchronized void onHashingStarted() {
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
        final List<VirtualLeafRecord<K, V>> dirtyLeavesToFlush;
        synchronized (this) {
            hashes.add(new VirtualHashRecord(path, hash));
            if ((flushInterval > 0) && (hashes.size() >= flushInterval) && flushInProgress.compareAndSet(false, true)) {
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
    public synchronized void onLeafHashed(final VirtualLeafRecord<K, V> leaf) {
        leaves.add(leaf);
    }

    @Override
    public void onHashingCompleted() {
        final List<VirtualHashRecord> finalNodesToFlush;
        final List<VirtualLeafRecord<K, V>> finalLeavesToFlush;
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
            @NonNull final List<VirtualHashRecord> hashesToFlush,
            @NonNull final List<VirtualLeafRecord<K, V>> leavesToFlush) {
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
                        leavesToFlush.stream().map(r -> r.toBytes(keySerializer, valueSerializer)),
                        findLeavesToRemove().map(r -> r.toBytes(keySerializer, valueSerializer)),
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

    /**
     * Find the leaves that need to be removed from the data source up to this moment.
     *
     * @return a stream of leaves to remove
     */
    protected abstract Stream<VirtualLeafRecord<K, V>> findLeavesToRemove();
}
