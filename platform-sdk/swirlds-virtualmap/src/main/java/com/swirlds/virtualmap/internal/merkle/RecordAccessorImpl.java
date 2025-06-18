// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Implementation of {@link RecordAccessor} which, given a state, cache, and data source, provides access
 * to all records.
 */
@SuppressWarnings("rawtypes")
public class RecordAccessorImpl implements RecordAccessor {

    private final VirtualMapMetadata state;
    private final VirtualNodeCache cache;
    private final VirtualDataSource dataSource;

    /**
     * Create a new {@link RecordAccessorImpl}.
     *
     * @param state
     * 		The state. Cannot be null.
     * @param cache
     * 		The cache. Cannot be null.
     * @param dataSource
     * 		The data source. Can be null.
     */
    public RecordAccessorImpl(
            final VirtualMapMetadata state, final VirtualNodeCache cache, final VirtualDataSource dataSource) {
        this.state = Objects.requireNonNull(state);
        this.cache = Objects.requireNonNull(cache);
        this.dataSource = dataSource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualMapMetadata getState() {
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualNodeCache getCache() {
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
        final Hash hash = cache.lookupHashByPath(path);
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
        final Hash hash = cache.lookupHashByPath(path);
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
    public VirtualLeafBytes findLeafRecord(final Bytes key) {
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
     * {@inheritDoc}
     */
    @Override
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
     * {@inheritDoc}
     */
    @Override
    public long findKey(final Bytes key) {
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
}
