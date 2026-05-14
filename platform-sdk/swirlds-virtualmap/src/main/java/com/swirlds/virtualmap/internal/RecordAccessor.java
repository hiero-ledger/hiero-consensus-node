// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafChunk;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.base.crypto.Hash;

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

    private final int hashChunkHeight;
    private final int leafChunkSize;

    private final VirtualNodeCache cache;
    private final VirtualDataSource dataSource;

    /**
     * Create a new {@link RecordAccessor}.
     *
     * @param state
     * 		The state. Cannot be null.
     * @param hashChunkHeight
     *      Hash chunk height
     * @param leafChunkSize
     *      Leaf chunk size
     * @param cache
     * 		The cache. Cannot be null.
     * @param dataSource
     * 		The data source. Can be null.
     */
    public RecordAccessor(
            @NonNull final VirtualMapMetadata state,
            final int hashChunkHeight,
            final int leafChunkSize,
            @NonNull final VirtualNodeCache cache,
            @NonNull final VirtualDataSource dataSource) {
        this.state = Objects.requireNonNull(state);
        this.hashChunkHeight = hashChunkHeight;
        this.leafChunkSize = leafChunkSize;
        this.cache = Objects.requireNonNull(cache);
        this.dataSource = dataSource;
    }

    // --- Hashes

    public int getHashChunkHeight() {
        return this.hashChunkHeight;
    }

    public Hash rootHash() {
        final long size = state.getSize();
        if (size == 0) {
            return null;
        }
        VirtualHashChunk rootChunk = cache.lookupHashChunkById(0);
        if (rootChunk == null) {
            try {
                rootChunk = dataSource.loadHashChunk(0);
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to read root hash chunk from data source", e);
            }
        }
        assert rootChunk != null;
        return rootChunk.chunkRootHash(state.getFirstLeafPath(), state.getLastLeafPath());
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
    @Nullable
    public Hash findHash(final long path) {
        assert path > 0;
        if ((path <= 0) || (path > state.getLastLeafPath())) {
            return null;
        }
        final long chunkId = VirtualHashChunk.pathToChunkId(path, hashChunkHeight);
        VirtualHashChunk hashChunk = cache.lookupHashChunkById(chunkId);
        if (hashChunk != null) {
            return hashChunk.calcHash(path, state.getFirstLeafPath(), state.getLastLeafPath());
        }
        try {
            hashChunk = dataSource.loadHashChunk(chunkId);
            if (hashChunk != null) {
                return hashChunk.calcHash(path, dataSource.getFirstLeafPath(), dataSource.getLastLeafPath());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read node hash from data source by path", e);
        }
        return null;
    }

    /**
     * Looks up a virtual hash chunk at the given chunk path.
     */
    public VirtualHashChunk findHashChunk(final long chunkPath) {
        assert chunkPath >= 0;
        if ((chunkPath < 0) || (chunkPath > state.getLastLeafPath())) {
            return null;
        }
        final long chunkId = VirtualHashChunk.chunkPathToChunkId(chunkPath, hashChunkHeight);
        VirtualHashChunk hashChunk = cache.lookupHashChunkById(chunkId);
        if (hashChunk != null) {
            return hashChunk;
        }
        try {
            hashChunk = dataSource.loadHashChunk(chunkId);
            return hashChunk;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read node hash from data source by path", e);
        }
    }

    // --- Leaves

    /**
     * Finds the path of the given key.
     * @param key The key. Must not be null.
     * @return The path or {@link Path#INVALID_PATH} if the key is not found.
     */
    public long findKeyPath(final @NonNull Bytes key) {
        long path = cache.findKeyPath(key);
        if (path != Path.UNKNOWN_PATH) {
            return path;
        }
        try {
            path = dataSource.findKey(key);
            // No UNKNOWN_PATH from the data source
            assert (path == Path.INVALID_PATH) || (path > Path.ROOT_PATH);
            return path;
        } catch (final IOException ex) {
            throw new UncheckedIOException("Failed to find key in the data source", ex);
        }
    }

    public <V> VirtualLeafBytes<V> findLeaf(final @NonNull Bytes key) {
        long path = findKeyPath(key);
        if (path == Path.INVALID_PATH) {
            return null;
        }
        return findLeaf(path);
    }

    /**
     * TODO
     * Locates and returns a leaf node based on the path. If the leaf
     * node already exists in memory, then the same instance is returned each time.
     * If the node is not in memory, then a new instance is returned. To save
     * it in memory, set <code>cache</code> to true. If the leaf cannot be found in
     * the data source, then null is returned.
     *
     * <p>This method may be called on an immutable virtual map copy, or from
     * VirtualMap's add() or remove() methods.
     *
     * @param path
     * 		The path
     * @return The leaf, or null if there is not one.
     * @throws UncheckedIOException
     * 		If we fail to access the data store, then a catastrophic error occurred and
     * 		an UncheckedIOException is thrown.
     */
    @Nullable
    // TODO: revisit all usages of findLeaf(path)
    public <V> VirtualLeafBytes<V> findLeaf(final long path) {
        assert path != Path.UNKNOWN_PATH;
        assert path != Path.INVALID_PATH;
        assert path != Path.ROOT_PATH;

        if (path < state.getFirstLeafPath() || path > state.getLastLeafPath()) {
            return null;
        }

        // Check the cache
        final VirtualLeafBytes<V> leafInCache = cache.lookupLeaf(path);
        if (leafInCache != null) {
            return leafInCache;
        }

        // Load from the data source
        try {
            return findLeafInDataSource(path);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Failed to read a leaf chunk from the data source", ex);
        }
    }

    private <V> VirtualLeafBytes<V> findLeafInDataSource(final long path) throws IOException {
        final long leafChunkId = VirtualLeafChunk.pathToChunkId(path, leafChunkSize);
        final VirtualLeafChunk leafChunk = dataSource.loadLeafChunk(leafChunkId);
        if (leafChunk == null) {
            // The path is valid. The leaf isn't in the cache, so it must be in
            // the data source
            throw new IllegalStateException("Failed to find a leaf at path = " + path);
        }
        assert leafChunk.containsPath(path)
                : "The chunk we found from the DB does not match the one we were looking for";
        final VirtualLeafBytes<V> leaf = leafChunk.getLeaf(path);
        assert leaf != null;
        return leaf;
    }

    // --- Misc

    /**
     * Closes this record accessor and releases all its resources.
     *
     * @throws IOException If an I/O error occurs
     */
    public void close() throws IOException {
        dataSource.close();
    }
}
