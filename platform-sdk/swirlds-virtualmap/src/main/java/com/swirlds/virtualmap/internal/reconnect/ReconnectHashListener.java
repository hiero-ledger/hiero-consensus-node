// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static java.util.Objects.requireNonNull;

import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.hash.VirtualHashListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * A {@link VirtualHashListener} and {@link LongFunction<VirtualHashChunk>} (as chunk preloaded) implementation used by the learner during reconnect.
 * During reconnect, the dirty leaves are sent from the teacher to the learner. Then the learner sends the leaves to a
 * {@link com.swirlds.virtualmap.internal.hash.VirtualHasher} to rehash the whole tree received from
 * the teacher. The hasher notifies this listener, which flushes the hashes to disk using {@link
 * ReconnectHashLeafFlusher} mechanism, which completely bypasses the {@link
 * com.swirlds.virtualmap.internal.cache.VirtualNodeCache} and the
 * {@link com.swirlds.virtualmap.internal.pipeline.VirtualPipeline} This is essential for performance
 * and memory reasons, since during reconnect we may need to process the entire data set, which is too
 * large to fit in memory.
 *
 */
public class ReconnectHashListener implements VirtualHashListener, LongFunction<VirtualHashChunk> {

    private final Map<Long, VirtualHashChunk> hashChunkMap = new ConcurrentHashMap<>();
    private final ReconnectHashLeafFlusher flusher;
    private final int hashChunkHeight;

    /**
     * Create a new {@link ReconnectHashListener}.
     *
     * @param flusher Hash / leaf flusher to use to flush data to disk
     */
    public ReconnectHashListener(@NonNull final ReconnectHashLeafFlusher flusher) {
        this.flusher = requireNonNull(flusher);
        hashChunkHeight = flusher.getDataSource().getHashChunkHeight();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHashingStarted(long firstLeafPath, long lastLeafPath) {
        flusher.start(firstLeafPath, lastLeafPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHashChunkHashed(@NonNull final VirtualHashChunk chunk) {
        flusher.updateHashChunk(chunk);
        hashChunkMap.remove(chunk.getChunkId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLeafHashed(final VirtualLeafBytes leaf) {
        flusher.updateLeaf(leaf);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHashingCompleted() {
        flusher.finish();
    }

    @Override
    public VirtualHashChunk apply(long path) {
        final long hashChunkId = VirtualHashChunk.chunkPathToChunkId(path, hashChunkHeight);

        return hashChunkMap.computeIfAbsent(hashChunkId, id -> {
            VirtualHashChunk chunk;
            try {
                chunk = flusher.getDataSource().loadHashChunk(id);
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
