// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.datasource;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Defines a data source, used with {@code VirtualMap}, to implement a virtual tree. Both in-memory and
 * on-disk data sources may be written. When constructing a {@code VirtualMap}, create a concrete data source
 * implementation.
 * <p>
 * The {@link VirtualDataSource} defines methods for getting the root node, and for looking up a leaf node
 * by key.
 * <p>
 * The nodes returned by the methods on this interface represent the *LATEST* state on disk. Once retrieved,
 * the nodes can be fast-copied for later versions, and persisted to disk via *archive*.
 * <p>
 * <strong>YOU MUST NEVER ASK FOR SOMETHING YOU HAVE NOT PREVIOUSLY WRITTEN.</strong> If you do, you will get
 * very strange exceptions. This is deemed acceptable because guarding against it would require obnoxious
 * performance degradation.
 */
public interface VirtualDataSource {

    /** nominal value for a invalid path */
    int INVALID_PATH = -1;

    /**
     * Close the data source and delete all its data.
     *
     * @throws IOException
     * 		If there was a problem closing the data source
     */
    default void close() throws IOException {
        close(false);
    }

    /**
     * Close the data source.
     *
     * @param keepData Indicates whether to keep data source data or not
     * @throws IOException
     * 		If there was a problem closing the data source
     */
    void close(boolean keepData) throws IOException;

    /**
     * Save a batch of data to data store.
     *
     * <p>If you call this method where not all data is provided to cover the change in
     * firstLeafPath and lastLeafPath, then any reads after this call may return rubbish or throw
     * obscure exceptions for any internals or leaves that have not been written. For example, if
     * you were to grow the tree by more than 2x, and then called this method in batches, be aware
     * that if you were to query for some record between batches that hadn't yet been saved, you
     * will encounter problems.
     *
     * @param firstLeafPath
     *      the tree path for first leaf
     * @param lastLeafPath
     *      the tree path for last leaf
     * @param hashChunksToUpdate
     * 		stream of dirty hash chunks to update
     * @param leafRecordsToAddOrUpdate
     * 		stream of new and updated leaf node bytes
     * @param leafRecordsToDelete
     * 		stream of new leaf node bytes to delete, The leaf record's key and path have to be
     * 		populated, all other data can be null
     * @param isReconnectContext if the save is in the context of a reconnect
     * @throws IOException If there was a problem saving changes to data source
     */
    void saveRecords(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final Stream<VirtualHashChunk> hashChunksToUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
            @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
            final boolean isReconnectContext)
            throws IOException;

    /**
     * Load virtual record bytes for a leaf node by key.
     *
     * @param keyBytes the key bytes for a leaf
     * @return the leaf's record if one was stored for the given key or null if not stored
     * @throws IOException if there was a problem reading the leaf record
     */
    @Nullable
    VirtualLeafBytes loadLeafRecord(final Bytes keyBytes) throws IOException;

    /**
     * Load virtual record bytes for a leaf node by path. If the path is outside the current
     * data source's leaf path range, this method returns {@code null}.
     *
     * @param path the path for a leaf
     * @return the leaf's record if one was stored for the given path or null if not stored
     * @throws IOException if there was a problem reading the leaf record
     */
    @Nullable
    VirtualLeafBytes loadLeafRecord(final long path) throws IOException;

    /**
     * Find the path of the given key.
     *
     * @param keyBytes the key bytes
     * @return the path or INVALID_PATH if the key is not stored
     * @throws IOException if there was a problem locating the key
     */
    long findKey(final Bytes keyBytes) throws IOException;

    /**
     * Load a virtual node hash chunk with the given ID.
     *
     * @param chunkId The chunk ID
     * @return The hash chunk, or {@code null} if no chunk was stored for the given ID
     * @throws IOException If there was a problem loading the hash chunk from data source
     */
    @Nullable
    VirtualHashChunk loadHashChunk(final long chunkId) throws IOException;

    /**
     * Write a snapshot of the current state of the database at this moment in time. This will need to be called between
     * calls to saveRecords to have a reliable state. This will block till the snapshot is completely created.
     * <p><b>
     * IMPORTANT, after this is completed the caller owns the directory. It is responsible for deleting it when it
     * is no longer needed.
     * </b></p>
     *
     * @param snapshotDirectory
     * 		Directory to put snapshot into, it will be created if it doesn't exist.
     * @throws IOException
     * 		If there was a problem writing the current database out to the given directory
     */
    void snapshot(final Path snapshotDirectory) throws IOException;

    /**
     * Switch this database instance to use the statistics registered in another database. Required due
     * to statistics restriction that prevents stats from being registered after initial boot up process.
     *
     * @param that
     * 		the database with statistics to copy
     */
    void copyStatisticsFrom(VirtualDataSource that);

    /**
     * Register all statistics with an object that manages statistics.
     *
     * @param metrics
     * 		reference to the metrics system
     */
    void registerMetrics(final Metrics metrics);

    /**
     * Enables background compaction process. Compaction starts on the next flush.
     */
    void enableBackgroundCompaction();

    /**
     * Cancels all compactions that are currently running and disables background compaction process.
     */
    void stopAndDisableBackgroundCompaction();

    /**
     * Returns the first leaf path stored in this data source.
     */
    long getFirstLeafPath();

    /**
     * Returns the last leaf path stored in this data source.
     */
    long getLastLeafPath();

    /**
     * Returns the height of hash chunks stored in this data source. If the data
     * source is empty, the value from {@link com.swirlds.virtualmap.config.VirtualMapConfig}
     * is returned.
     */
    int getHashChunkHeight();
}
