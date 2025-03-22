// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.VirtualStateAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * Implementation of {@link RecordAccessor} which, given a state, cache, and data source, provides access
 * to all records.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public class RecordAccessorImpl<K extends VirtualKey, V extends VirtualValue> implements RecordAccessor<K, V> {

    private final VirtualStateAccessor state;
    private final VirtualNodeCache<K, V> cache;
    private final KeySerializer<K> keySerializer;
    private final ValueSerializer<V> valueSerializer;
    private final VirtualDataSource dataSource;

    /**
     * Create a new {@link RecordAccessorImpl}.
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
    public RecordAccessorImpl(
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
     * {@inheritDoc}
     */
    @Override
    public VirtualStateAccessor getState() {
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualNodeCache<K, V> getCache() {
        return cache;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualDataSource getDataSource() {
        return dataSource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash findHash(final long path) {
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
     * {@inheritDoc}
     */
    @Override
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
     * {@inheritDoc}
     */
    @Override
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
     * {@inheritDoc}
     */
    @Override
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
     * {@inheritDoc}
     */
    @Override
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
}
