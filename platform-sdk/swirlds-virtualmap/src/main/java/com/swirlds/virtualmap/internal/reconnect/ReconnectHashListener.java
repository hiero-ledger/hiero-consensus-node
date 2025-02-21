// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import com.swirlds.virtualmap.internal.merkle.AbstractHashListener;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link VirtualHashListener} implementation used by the learner during reconnect. During reconnect, the dirty
 * leaves will be sent from the teacher to the learner in a breadth-first order. The hashing algorithm in the
 * {@link com.swirlds.virtualmap.internal.hash.VirtualHasher} is setup to hash enormous trees in breadth-first order.
 * As the hasher hashes, it notifies this listener which then stores up the changes into different sorted lists.
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
public class ReconnectHashListener<K extends VirtualKey, V extends VirtualValue> extends AbstractHashListener<K, V> {
    private final ReconnectNodeRemover<K, V> nodeRemover;

    /**
     * Create a new {@link ReconnectHashListener}.
     *
     * @param firstLeafPath
     * 		The first leaf path. Must be a valid path.
     * @param lastLeafPath
     * 		The last leaf path. Must be a valid path.
     * @param dataSource
     * 		The data source. Cannot be null.
     * @param reconnectFlushInterval
     *      The number of nodes to hash before they are flushed to disk.
     * @param statistics
     *      Virtual map stats. Cannot be null.
     */
    public ReconnectHashListener(
            final long firstLeafPath,
            final long lastLeafPath,
            final KeySerializer<K> keySerializer,
            final ValueSerializer<V> valueSerializer,
            @NonNull final VirtualDataSource dataSource,
            final int reconnectFlushInterval,
            @NonNull final VirtualMapStatistics statistics,
            @NonNull final ReconnectNodeRemover<K, V> nodeRemover) {
        super(
                firstLeafPath,
                lastLeafPath,
                keySerializer,
                valueSerializer,
                dataSource,
                reconnectFlushInterval,
                statistics);
        this.nodeRemover = Objects.requireNonNull(nodeRemover);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Stream<VirtualLeafRecord<K, V>> findLeavesToRemove() {
        return nodeRemover.getRecordsToDelete();
    }
}
