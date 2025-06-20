// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Provides access to all records.
 * @param <K>
 *     The key
 * @param <V>
 *     The value
 */
public interface RecordAccessor<K extends VirtualKey, V extends VirtualValue> {

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
    Hash findHash(long path);

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
    boolean findAndWriteHash(long path, SerializableDataOutputStream out) throws IOException;

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
    VirtualLeafRecord<K, V> findLeafRecord(final K key, final boolean copy);

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
    VirtualLeafRecord<K, V> findLeafRecord(final long path, final boolean copy);

    /**
     * Finds the path of the given key.
     * @param key
     * 		The key. Must not be null.
     * @return The path or INVALID_PATH if the key is not found.
     */
    long findKey(final K key);

    /**
     * Gets the data source backed by this {@link RecordAccessor}
     *
     * @return
     * 		The data source. Will not be null.
     */
    VirtualDataSource getDataSource(); // I'd actually like to remove this some day...

    /**
     * Gets the state.
     *
     * @return The state. This will never be null.
     */
    VirtualStateAccessor getState();

    /**
     * Gets the cache.
     *
     * @return
     * 		The cache. This will never be null.
     */
    VirtualNodeCache<K, V> getCache();
}
