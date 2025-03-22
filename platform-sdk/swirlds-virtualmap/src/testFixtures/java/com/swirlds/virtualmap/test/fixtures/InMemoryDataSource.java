// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.hiero.consensus.model.crypto.Hash;

/**
 * In memory implementation of VirtualDataSource for use in testing.
 */
public class InMemoryDataSource implements VirtualDataSource {

    private static final String NEGATIVE_PATH_MESSAGE = "path is less than 0";

    private final String name;
    private final ConcurrentHashMap<Long, Hash> hashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, VirtualLeafBytes> leafRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Bytes, Long> keyToPathMap = new ConcurrentHashMap<>();
    private volatile long firstLeafPath = -1;
    private volatile long lastLeafPath = -1;
    private volatile boolean closed = false;

    private boolean failureOnHashLookup = false;
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
        this.hashes.putAll(copy.hashes);
        this.leafRecords.putAll(copy.leafRecords);
        this.keyToPathMap.putAll(copy.keyToPathMap);
    }

    public String getName() {
        return name;
    }

    /**
     * Close the data source
     */
    @Override
    public void close(final boolean keepData) {
        if (!keepData) {
            hashes.clear();
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
            @NonNull final Stream<VirtualHashRecord> pathHashRecordsToUpdate,
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

        deleteInternalRecords(firstLeafPath);
        deleteLeafRecords(leafRecordsToDelete, isReconnectContext);
        saveInternalRecords(lastLeafPath, pathHashRecordsToUpdate);
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
    public VirtualLeafBytes loadLeafRecord(final Bytes key, final int keyHashCode) throws IOException {
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
     * @param keyHashCode the key hash code
     * @return the path or INVALID_PATH if not stored
     * @throws IOException
     * 		If there was a problem locating the key
     */
    @Override
    public long findKey(final Bytes key, final int keyHashCode) throws IOException {
        final Long path = keyToPathMap.get(key);
        return (path == null) ? INVALID_PATH : path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash loadHash(long path) throws IOException {
        if (failureOnHashLookup) {
            throw new IOException("Preconfigured failure on hash lookup");
        }

        if (path < 0) {
            throw new IllegalArgumentException(NEGATIVE_PATH_MESSAGE);
        }

        // It may be that some code is trying to iterate over an internal node that has never
        // been created or saved. We have to be prepared for that case.
        if (path != 0 && path > lastLeafPath) {
            return null;
        }

        return hashes.get(path);
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

    private void saveInternalRecords(final long maxValidPath, final Stream<VirtualHashRecord> pathHashRecords)
            throws IOException {
        final var itr = pathHashRecords.iterator();
        while (itr.hasNext()) {
            final var rec = itr.next();
            final var path = rec.path();
            final var hash = Objects.requireNonNull(rec.hash(), "The hash of a saved internal record cannot be null");

            if (path < 0) {
                throw new IOException("Internal record for " + path + " is bogus. It cannot be < 0");
            }

            if (path > maxValidPath) {
                throw new IOException(
                        "Internal record for " + path + " is bogus. It cannot be > last leaf path " + maxValidPath);
            }

            this.hashes.put(path, hash);
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
            final var keyHashCode = rec.keyHashCode();
            final var value = rec.valueBytes(); // Not sure if this can be null or not.

            if (path < firstLeafPath) {
                throw new IOException(
                        "Leaf record for " + path + " is bogus. It cannot be < first leaf path " + firstLeafPath);
            }
            if (path > lastLeafPath) {
                throw new IOException(
                        "Leaf record for " + path + " is bogus. It cannot be > last leaf path " + lastLeafPath);
            }

            this.leafRecords.put(path, new VirtualLeafBytes(path, key, keyHashCode, value));
            this.keyToPathMap.put(key, path);
        }
    }

    private void deleteInternalRecords(final long firstLeafPath) {
        for (long i = firstLeafPath; i < this.firstLeafPath; i++) {
            this.hashes.remove(i);
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

    public void setFailureOnHashLookup(boolean failureOnHashLookup) {
        this.failureOnHashLookup = failureOnHashLookup;
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

    @Override
    @SuppressWarnings("rawtypes")
    public KeySerializer getKeySerializer() {
        throw new UnsupportedOperationException("This method should never be called");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public ValueSerializer getValueSerializer() {
        throw new UnsupportedOperationException("This method should never be called");
    }
}
