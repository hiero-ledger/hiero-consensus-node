// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafChunk;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In memory implementation of VirtualDataSource for use in testing.
 */
public class InMemoryDataSource implements VirtualDataSource {

    // This doesn't have to match VirtualMapConfig#hashChunkHeight
    private static final int DEFAULT_HASH_CHUNK_HEIGHT = 3;

    // This doesn't have to match VirtualMapConfig#leafChunkSize
    private static final int DEFAULT_LEAF_CHUNK_SIZE = 10;

    private static final String NEGATIVE_CHUNKID_MESSAGE = "chunk ID is less than 0";
    private static final String NEGATIVE_PATH_MESSAGE = "path is less than 0";

    private final String name;

    // Hash chunks by ID
    private final ConcurrentHashMap<Long, VirtualHashChunk> hashChunks = new ConcurrentHashMap<>();
    // Leaf chunks by ID
    private final ConcurrentHashMap<Long, VirtualLeafChunk> leafChunks = new ConcurrentHashMap<>();
    // Leaf paths by key
    private final ConcurrentHashMap<Bytes, Long> keyToPathMap = new ConcurrentHashMap<>();

    private volatile long firstLeafPath = -1;
    private volatile long lastLeafPath = -1;

    private volatile boolean closed = false;

    private boolean failureOnHashChunkLookup = false;
    private boolean failureOnSave = false;
    private boolean failureOnLeafRecordLookup = false;

    /**
     * Create a new InMemoryDataSource
     *
     * @param name
     * 		data source name
     */
    public InMemoryDataSource(final String name) {
        this.name = name;
    }

    public InMemoryDataSource(InMemoryDataSource copy) {
        this.name = copy.name;
        this.firstLeafPath = copy.firstLeafPath;
        this.lastLeafPath = copy.lastLeafPath;
        this.hashChunks.putAll(copy.hashChunks);
        this.leafChunks.putAll(copy.leafChunks);
        this.keyToPathMap.putAll(copy.keyToPathMap);
    }

    /**
     * Close the data source
     */
    @Override
    public void close(final boolean keepData) {
        if (!keepData) {
            hashChunks.clear();
            leafChunks.clear();
            keyToPathMap.clear();
        }
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void saveRecords(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final Stream<VirtualHashChunk> hashes,
            @NonNull final Stream<VirtualLeafChunk> leaves,
            @NonNull final Stream<VirtualLeafBytes> deletedLeaves,
            final boolean isReconnectContext)
            throws IOException {
        if (failureOnSave) {
            throw new IOException("Preconfigured failure on save");
        }

        if (closed) {
            throw new IOException("Data Source is closed");
        }

        if (firstLeafPath < 1 && firstLeafPath != -1) {
            throw new IllegalArgumentException("An illegal first leaf path was provided: " + firstLeafPath);
        }

        final var validLastLeafPath = (lastLeafPath == firstLeafPath && (lastLeafPath == -1 || lastLeafPath == 1))
                || lastLeafPath > firstLeafPath;
        if (!validLastLeafPath) {
            throw new IllegalArgumentException("An illegal last leaf path was provided. lastLeafPath=" + lastLeafPath
                    + ", firstLeafPath=" + firstLeafPath);
        }

        deleteLeafRecords(deletedLeaves, isReconnectContext);
        saveInternalRecords(lastLeafPath, hashes);
        saveLeafRecords(firstLeafPath, lastLeafPath, leaves);
        // Save the leaf paths for later validation checks and to let us know when to delete internals
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualLeafChunk loadLeafChunk(final long leafChunkId) throws IOException {
        if (leafChunkId < 0) {
            throw new IllegalArgumentException(NEGATIVE_CHUNKID_MESSAGE);
        }

        if (failureOnLeafRecordLookup) {
            throw new IOException("Preconfigured failure on leaf record lookup");
        }

        if (!VirtualLeafChunk.inRange(leafChunkId, DEFAULT_LEAF_CHUNK_SIZE, firstLeafPath, lastLeafPath)) {
            throw new IllegalArgumentException(
                    "Leaf chunk " + leafChunkId + " is not in range [" + firstLeafPath + ", " + lastLeafPath + "]");
        }

        final VirtualLeafChunk chunk = leafChunks.get(leafChunkId);
        assert chunk != null
                : "When looking up leaves, we should never be asked to look up a chunk that doesn't exist. ID=" + leafChunkId;
        return chunk;
    }

    /**
     * Find the path of the given key
     * @param key the key for a path
     * @return the path or INVALID_PATH if not stored
     * @throws IOException
     * 		If there was a problem locating the key
     */
    @Override
    public long findKey(final Bytes key) throws IOException {
        final Long path = keyToPathMap.get(key);
        return (path == null) ? INVALID_PATH : path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualHashChunk loadHashChunk(long hashChunkId) throws IOException {
        if (failureOnHashChunkLookup) {
            throw new IOException("Preconfigured failure on hash lookup");
        }

        if (hashChunkId < 0) {
            throw new IllegalArgumentException(NEGATIVE_CHUNKID_MESSAGE);
        }

        final VirtualHashChunk c = hashChunks.get(hashChunkId);
        return c == null ? null : c.copy();
    }

    /**
     * This is a no-op implementation.
     */
    @Override
    public void snapshot(final Path snapshotDirectory) {
        // nop
    }

    /**
     * This database has no statistics.
     */
    @Override
    public void copyStatisticsFrom(final VirtualDataSource that) {
        // nop
    }

    /**
     * This database has no statistics.
     */
    @Override
    public void registerMetrics(final Metrics metrics) {
        // nop
    }

    // =================================================================================================================
    // private methods

    private void saveInternalRecords(final long maxValidPath, final Stream<VirtualHashChunk> hashChunks)
            throws IOException {
        final var itr = hashChunks.iterator();
        while (itr.hasNext()) {
            final var hashChunk = itr.next();
            final var path = hashChunk.path();

            if (path < 0) {
                throw new IOException("Internal record for " + path + " is bogus. It cannot be < 0");
            }

            if (path > maxValidPath) {
                throw new IOException(
                        "Internal record for " + path + " is bogus. It cannot be > last leaf path " + maxValidPath);
            }

            final long chunkId = hashChunk.getChunkId();
            this.hashChunks.put(chunkId, hashChunk);
        }
    }

    private void saveLeafRecords(
            final long firstLeafPath, final long lastLeafPath, final Stream<VirtualLeafChunk> leaves)
            throws IOException {
        final var itr = leaves.iterator();
        while (itr.hasNext()) {
            final var chunk = itr.next();
            final var id = chunk.id();
            if (!VirtualLeafChunk.inRange(id, DEFAULT_LEAF_CHUNK_SIZE, firstLeafPath, lastLeafPath)) {
                throw new IllegalArgumentException(
                        "Leaf chunk " + id + " is not in range [" + firstLeafPath + ", " + lastLeafPath + "]");
            }
            this.leafChunks.put(id, chunk.copy());

            final long firstChunkPath = chunk.getFirstPath();
            for (int i = 0; i < chunk.size(); i++) {
                final VirtualLeafBytes leaf = chunk.getLeaf(firstChunkPath + i);
                this.keyToPathMap.put(leaf.keyBytes(), leaf.path());
            }
        }
    }

    private void deleteLeafRecords(
            final Stream<VirtualLeafBytes> leafRecordsToDelete, final boolean isReconnectContext) {
        final var itr = leafRecordsToDelete.iterator();
        while (itr.hasNext()) {
            final var rec = itr.next();
            final long path = rec.path();
            final Bytes key = rec.keyBytes();
            final Long oldPath = keyToPathMap.get(key);
            if (oldPath == null) {
                continue;
            }
            if (!isReconnectContext || path == oldPath) {
                this.keyToPathMap.remove(key);
                this.leafChunks.remove(path);
            }
        }
    }

    @Override
    public long getFirstLeafPath() {
        return firstLeafPath;
    }

    @Override
    public long getLastLeafPath() {
        return lastLeafPath;
    }

    @Override
    public int getHashChunkHeight() {
        return DEFAULT_HASH_CHUNK_HEIGHT;
    }

    @Override
    public int getLeafChunkSize() {
        return DEFAULT_LEAF_CHUNK_SIZE;
    }

    @Override
    public void enableBackgroundCompaction() {
        // no op
    }

    @Override
    public void stopAndDisableBackgroundCompaction() {
        // no op
    }
}
