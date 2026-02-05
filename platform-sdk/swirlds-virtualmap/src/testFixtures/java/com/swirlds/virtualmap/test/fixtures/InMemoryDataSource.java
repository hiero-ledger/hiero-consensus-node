// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
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

    private static final String NEGATIVE_CHUNKID_MESSAGE = "chunk ID is less than 0";
    private static final String NEGATIVE_PATH_MESSAGE = "path is less than 0";

    private final String name;

    // Hash chunks by ID
    private final ConcurrentHashMap<Long, VirtualHashChunk> hashChunks = new ConcurrentHashMap<>();
    // Leaf records by path
    private final ConcurrentHashMap<Long, VirtualLeafBytes> leafRecords = new ConcurrentHashMap<>();
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
        this.leafRecords.putAll(copy.leafRecords);
        this.keyToPathMap.putAll(copy.keyToPathMap);
    }

    /**
     * Close the data source
     */
    @Override
    public void close(final boolean keepData) {
        if (!keepData) {
            hashChunks.clear();
            leafRecords.clear();
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
            @NonNull final Stream<VirtualHashChunk> hashChunksToUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
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

        deleteLeafRecords(leafRecordsToDelete, isReconnectContext);
        saveInternalRecords(lastLeafPath, hashChunksToUpdate);
        saveLeafRecords(firstLeafPath, lastLeafPath, leafRecordsToAddOrUpdate);
        // Save the leaf paths for later validation checks and to let us know when to delete internals
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
    }

    /**
     * Load the record for a leaf node by key
     *
     * @param key
     * 		the key for a leaf
     * @return the leaf's record if one was stored for the given key or null if not stored
     * @throws IOException
     * 		If there was a problem reading the leaf record
     */
    @Override
    public VirtualLeafBytes loadLeafRecord(final Bytes key) throws IOException {
        Objects.requireNonNull(key, "Key cannot be null");
        final Long path = keyToPathMap.get(key);
        if (path == null) {
            return null;
        }
        assert path >= firstLeafPath && path <= lastLeafPath : "Found an illegal path in keyToPathMap!";
        return loadLeafRecord(path);
    }

    /**
     * Load the record for a leaf node by path
     *
     * @param path
     * 		the path for a leaf
     * @return the leaf's record if one was stored for the given path or null if not stored
     * @throws IOException
     * 		If there was a problem reading the leaf record
     */
    @Override
    public VirtualLeafBytes loadLeafRecord(final long path) throws IOException {
        if (path < 0) {
            throw new IllegalArgumentException(NEGATIVE_PATH_MESSAGE);
        }

        if (failureOnLeafRecordLookup) {
            throw new IOException("Preconfigured failure on leaf record lookup");
        }

        if (path < firstLeafPath) {
            throw new IllegalArgumentException(
                    "path[" + path + "] is less than the firstLeafPath[" + firstLeafPath + "]");
        }

        if (path > lastLeafPath) {
            throw new IllegalArgumentException(
                    "path[" + path + "] is larger than the lastLeafPath[" + lastLeafPath + "]");
        }

        final VirtualLeafBytes rec = leafRecords.get(path);
        assert rec != null
                : "When looking up leaves, we should never be asked to look up a leaf that doesn't exist. path=" + path;
        return rec;
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
    public VirtualHashChunk loadHashChunk(long chunkId) throws IOException {
        if (failureOnHashChunkLookup) {
            throw new IOException("Preconfigured failure on hash lookup");
        }

        if (chunkId < 0) {
            throw new IllegalArgumentException(NEGATIVE_CHUNKID_MESSAGE);
        }

        final VirtualHashChunk c = hashChunks.get(chunkId);
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
            final long firstLeafPath, final long lastLeafPath, final Stream<VirtualLeafBytes> leafRecords)
            throws IOException {
        final var itr = leafRecords.iterator();
        while (itr.hasNext()) {
            final var rec = itr.next();
            final var path = rec.path();
            final var key = Objects.requireNonNull(rec.keyBytes(), "Key cannot be null");
            final var value = rec.valueBytes(); // Not sure if this can be null or not.

            if (path < firstLeafPath) {
                throw new IOException(
                        "Leaf record for " + path + " is bogus. It cannot be < first leaf path " + firstLeafPath);
            }
            if (path > lastLeafPath) {
                throw new IOException(
                        "Leaf record for " + path + " is bogus. It cannot be > last leaf path " + lastLeafPath);
            }

            this.leafRecords.put(path, new VirtualLeafBytes(path, key, value));
            this.keyToPathMap.put(key, path);
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
                this.leafRecords.remove(path);
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

    public void setFailureOnHashChunkLookup(boolean failureOnHashChunkLookup) {
        this.failureOnHashChunkLookup = failureOnHashChunkLookup;
    }

    public void setFailureOnSave(boolean failureOnSave) {
        this.failureOnSave = failureOnSave;
    }

    public void setFailureOnLeafRecordLookup(boolean failureOnLeafRecordLookup) {
        this.failureOnLeafRecordLookup = failureOnLeafRecordLookup;
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
