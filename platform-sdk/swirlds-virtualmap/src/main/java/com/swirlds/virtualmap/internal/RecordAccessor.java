// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Utility class that provides access to virtual records. Recently updated virtual records
 * are in virtual node cache, others are on disk (in the data source). This class provides
 * a layer on top of the cache and the data source. Every request is first sent to the
 * cache. If the cache doesn't contain the requested record, it is looked up in the data
 * source.
 */
@SuppressWarnings("rawtypes")
public final class RecordAccessor {

    private final VirtualMapMetadata state;
    private final VirtualNodeCache cache;
    private final VirtualDataSource dataSource;

    /**
     * Create a new {@link RecordAccessor}.
     *
     * @param state
     * 		The state. Cannot be null.
     * @param cache
     * 		The cache. Cannot be null.
     * @param dataSource
     * 		The data source. Can be null.
     */
    public RecordAccessor(
            @NonNull final VirtualMapMetadata state,
            @NonNull final VirtualNodeCache cache,
            @NonNull final VirtualDataSource dataSource) {
        this.state = Objects.requireNonNull(state);
        this.cache = Objects.requireNonNull(cache);
        this.dataSource = dataSource;
    }

    /**
     * Gets the {@link Hash} at a given path. If there is no record at the path, null is returned.
     *
     * @param path
     * 		Virtual node path
     * @return
     * 		Null if the virtual record doesn't exist. Either the path is bad, or the record has been deleted,
     * 		or the record has never been created.
     * @throws UncheckedIOException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		an UncheckedIOException is thrown.
     */
    public Hash findHash(final long path) {
        assert path >= 0;
        final Hash hash = cache.lookupHashByPath(path);
        if (hash != null) {
            return hash;
        }
        try {
            return dataSource.loadHash(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read node hash from data source by path", e);
        }
    }

    /**
     * Looks up a virtual node hash for a given path. If the hash is found, writes it to a
     * specified output stream.
     *
     * <p>Written bytes must be 100% identical to how hashes are serialized using {@link
     * Hash#serialize(SerializableDataOutputStream)} method.
     *
     * @param path Virtual node path
     * @param out Output stream to write the hash to
     * @return If the hash is found and written to the stream
     * @throws IOException If an I/O error occurred
     */
    public boolean findAndWriteHash(long path, SerializableDataOutputStream out) throws IOException {
        assert path >= 0;
        final Hash hash = cache.lookupHashByPath(path);
        if (hash != null) {
            hash.serialize(out);
            return true;
        }
        return dataSource.loadAndWriteHash(path, out);
    }

    /**
     * Locates and returns a leaf node based on the given key. If the leaf
     * node already exists in memory, then the same instance is returned each time.
     * If the node is not in memory, then a new instance is returned. To save
     * it in memory, set <code>cache</code> to true. If the key cannot be found in
     * the data source, then null is returned.
     *
     * @param key The key. Must not be null.
     * @return The leaf, or null if there is not one.
     * @throws UncheckedIOException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		an UncheckedIOException is thrown.
     */
    @Nullable
    public VirtualLeafBytes findLeafRecord(final @NonNull Bytes key) {
        VirtualLeafBytes rec = cache.lookupLeafByKey(key);
        if (rec == null) {
            try {
                rec = dataSource.loadLeafRecord(key);
                if (rec != null) {
                    assert rec.keyBytes().equals(key)
                            : "The key we found from the DB does not match the one we were looking for! key=" + key;
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException("Failed to read a leaf record from the data source by key", ex);
            }
        }

        return rec == VirtualNodeCache.DELETED_LEAF_RECORD ? null : rec;
    }

    /**
     * Locates and returns a leaf node based on the path. If the leaf
     * node already exists in memory, then the same instance is returned each time.
     * If the node is not in memory, then a new instance is returned. To save
     * it in memory, set <code>cache</code> to true. If the leaf cannot be found in
     * the data source, then null is returned.
     *
     * @param path
     * 		The path
     * @return The leaf, or null if there is not one.
     * @throws UncheckedIOException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		an UncheckedIOException is thrown.
     */
    @Nullable
    public VirtualLeafBytes findLeafRecord(final long path) {
        assert path != INVALID_PATH;
        assert path != ROOT_PATH;

        if (path < state.getFirstLeafPath() || path > state.getLastLeafPath()) {
            return null;
        }

        VirtualLeafBytes rec = cache.lookupLeafByPath(path);
        if (rec == null) {
            try {
                rec = dataSource.loadLeafRecord(path);
                if (rec != null) {
                    assert rec.path() == path
                            : "The path we found from the DB does not match the one we were looking for! path=" + path;
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException("Failed to read a leaf record from the data source by path", ex);
            }
        }

        return rec == VirtualNodeCache.DELETED_LEAF_RECORD ? null : rec;
    }

    /**
     * Finds the path of the given key.
     * @param key The key. Must not be null.
     * @return The path or INVALID_PATH if the key is not found.
     */
    public long findPath(final @NonNull Bytes key) {
        final VirtualLeafBytes rec = cache.lookupLeafByKey(key);
        if (rec != null) {
            return rec.path();
        }
        try {
            return dataSource.findKey(key);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Failed to find key in the data source", ex);
        }
    }

    /**
     * Closes this record accessor and releases all its resources.
     *
     * @throws IOException If an I/O error occurs
     */
    public void close() throws IOException {
        dataSource.close();
    }
}
