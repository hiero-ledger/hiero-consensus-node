// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
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
 *
 * @param <K>
 *     The key
 * @param <V>
 *     The value
 */
public final class RecordAccessor<K extends VirtualKey, V extends VirtualValue> {

    private final VirtualStateAccessor state;
    private final VirtualNodeCache<K, V> cache;
    private final KeySerializer<K> keySerializer;
    private final ValueSerializer<V> valueSerializer;
    private final VirtualDataSource dataSource;

    /**
     * Create a new {@link RecordAccessor}.
     *
     * @param state
     * 		The state. Cannot be null.
     * @param cache
     * 		The cache. Cannot be null.
     * @param keySerializer
     *      The key serializer. Can be null.
     * @param valueSerializer
     *      The value serializer. Can be null.
     * @param dataSource
     * 		The data source. Can be null.
     */
    public RecordAccessor(
            final VirtualStateAccessor state,
            final VirtualNodeCache<K, V> cache,
            final KeySerializer<K> keySerializer,
            final ValueSerializer<V> valueSerializer,
            final VirtualDataSource dataSource) {
        this.state = Objects.requireNonNull(state);
        this.cache = Objects.requireNonNull(cache);
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
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
    public Hash findHash(long path) {
        assert path >= 0;
        final Hash hash = cache.lookupHashByPath(path, false);
        if (hash == VirtualNodeCache.DELETED_HASH) {
            return null;
        }
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
        final Hash hash = cache.lookupHashByPath(path, false);
        if (hash == VirtualNodeCache.DELETED_HASH) {
            return false;
        }
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
     * @param key
     * 		The key. Must not be null.
     * @param copy
     * 		Whether to make a fast copy if needed.
     * @return The leaf, or null if there is not one.
     * @throws UncheckedIOException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		an UncheckedIOException is thrown.
     */
    public VirtualLeafRecord<K, V> findLeafRecord(final K key, final boolean copy) {
        VirtualLeafRecord<K, V> rec = cache.lookupLeafByKey(key, copy);
        if (rec == null) {
            try {
                final Bytes keyBytes = keySerializer.toBytes(key);
                final VirtualLeafBytes leafBytes = dataSource.loadLeafRecord(keyBytes, key.hashCode());
                if (leafBytes != null) {
                    rec = leafBytes.toRecord(keySerializer, valueSerializer);
                    assert rec.getKey().equals(key)
                            : "The key we found from the DB does not match the one we were looking for! key=" + key;
                    if (copy) {
                        cache.putLeaf(rec);
                    }
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
     * @param copy
     * 		Whether to make a fast copy if needed.
     * @return The leaf, or null if there is not one.
     * @throws UncheckedIOException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		an UncheckedIOException is thrown.
     */
    public VirtualLeafRecord<K, V> findLeafRecord(final long path, final boolean copy) {
        assert path != INVALID_PATH;
        assert path != ROOT_PATH;

        if (path < state.getFirstLeafPath() || path > state.getLastLeafPath()) {
            return null;
        }

        VirtualLeafRecord<K, V> rec = cache.lookupLeafByPath(path, copy);
        if (rec == null) {
            try {
                final VirtualLeafBytes leafBytes = dataSource.loadLeafRecord(path);
                if (leafBytes != null) {
                    rec = leafBytes.toRecord(keySerializer, valueSerializer);
                    if (copy) {
                        cache.putLeaf(rec);
                    }
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException("Failed to read a leaf record from the data source by path", ex);
            }
        }

        return rec == VirtualNodeCache.DELETED_LEAF_RECORD ? null : rec;
    }

    /**
     * Finds the path of the given key.
     * @param key
     * 		The key. Must not be null.
     * @return The path or INVALID_PATH if the key is not found.
     */
    public long findKey(final K key) {
        final VirtualLeafRecord<K, V> rec = cache.lookupLeafByKey(key, false);
        if (rec != null) {
            return rec.getPath();
        }
        try {
            final Bytes keyBytes = keySerializer.toBytes(key);
            return dataSource.findKey(keyBytes, key.hashCode());
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
