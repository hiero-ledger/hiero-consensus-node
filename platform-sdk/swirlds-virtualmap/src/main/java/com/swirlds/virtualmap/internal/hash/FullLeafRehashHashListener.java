// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.internal.HashLeafFlusher;
import com.swirlds.virtualmap.internal.VirtualMapStatistics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link VirtualHashListener} that is used during a full leaf rehash of a {@link VirtualMap}.
 *
 * <p>This listener receives complete hash chunks, typically produced by {@link HashChunkCollector}
 * during a full rehash, and passes them to a {@link HashLeafFlusher}, which flushes them to the
 * underlying {@link VirtualDataSource} in batches. Leaf records are not changed during a full
 * rehash, so they are never flushed.
 *
 * <p>All flushes, including the final one, are synchronous: {@link #onHashChunkHashed(VirtualHashChunk)}
 * and {@link #onHashingCompleted()} return only after flushed hashes are written to the data source.
 * If a flush fails, or the flushing thread is interrupted, these methods throw an exception.
 */
public class FullLeafRehashHashListener implements VirtualHashListener {

    private final HashLeafFlusher flusher;

    /**
     * Create a new {@link FullLeafRehashHashListener}.
     *
     * @param dataSource
     * 		The data source where new hashes will be saved. Cannot be null.
     * @param statistics
     *      Statistics object to record flush latency. Cannot be null.
     * @param flushInterval
     *      The number of hash slots in collected chunks to trigger a flush, see {@link HashLeafFlusher}.
     */
    public FullLeafRehashHashListener(
            @NonNull final VirtualDataSource dataSource,
            @NonNull final VirtualMapStatistics statistics,
            final int flushInterval) {
        this.flusher = new HashLeafFlusher(dataSource, flushInterval, statistics);
    }

    @Override
    public void onHashingStarted(final long firstLeafPath, final long lastLeafPath) {
        flusher.init(firstLeafPath, lastLeafPath);
    }

    @Override
    public void onHashChunkHashed(@NonNull final VirtualHashChunk chunk) {
        flusher.updateHashChunk(chunk);
    }

    @Override
    public void onHashingCompleted() {
        flusher.finish();
    }
}
