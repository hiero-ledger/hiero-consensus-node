// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * A {@link LongFunction<VirtualHashChunk>} implementation that preloads hash chunks from a {@link VirtualDataSource} and caches them in memory.
 * {@link #clearCache(long)} can be used to clear hash chunk cache when chunk is processed.
 */
public class DataSourceHashChunkPreloader implements LongFunction<VirtualHashChunk> {

    private final VirtualDataSource dataSource;
    private final int hashChunkHeight;

    /**
     * Hash chunk id to chunk map.
     */
    private final Map<Long, VirtualHashChunk> hashChunkMap = new ConcurrentHashMap<>();

    public DataSourceHashChunkPreloader(@NonNull VirtualDataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        hashChunkHeight = dataSource.getHashChunkHeight();
    }

    /**
     * Clear the cached hash chunk with the given id.
     * @param hashChunkId hash chunk id
     */
    public void clearCache(long hashChunkId) {
        hashChunkMap.remove(hashChunkId);
    }

    @Override
    public VirtualHashChunk apply(long path) {
        final long hashChunkId = VirtualHashChunk.chunkPathToChunkId(path, hashChunkHeight);

        return hashChunkMap.computeIfAbsent(hashChunkId, id -> {
            VirtualHashChunk chunk;
            try {
                chunk = dataSource.loadHashChunk(id);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (chunk == null) {
                final long hashChunkPath = VirtualHashChunk.chunkIdToChunkPath(hashChunkId, hashChunkHeight);
                chunk = new VirtualHashChunk(hashChunkPath, hashChunkHeight);
            }
            return chunk;
        });
    }
}
