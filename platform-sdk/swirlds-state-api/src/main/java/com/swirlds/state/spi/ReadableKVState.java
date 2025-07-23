// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Set;

/**
 * Provides access to key/value state for a service implementation. This interface is implemented by
 * the Hedera application, and provided to the service implementation at the appropriate times. The
 * methods of this class provide read access to the state.
 *
 * <p>Null values <strong>cannot be stored</strong> in this state. Null is used to indicate the
 * absence of a value.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public interface ReadableKVState<K, V> extends ReadableState {

    /**
     * Gets whether the given key exists in this {@link ReadableKVState}.
     *
     * @param key The key. Cannot be null, otherwise an exception is thrown.
     * @return true if the key exists in the state.
     */
    default boolean contains(@NonNull final K key) {
        return get(key) != null;
    }

    /**
     * Gets the value associated with the given key in a <strong>READ-ONLY</strong> way. The
     * returned value will be null if the key does not exist in the state, or if the key did exist
     * but the data had expired.
     *
     * @param key The key. Cannot be null, otherwise an exception is thrown.
     * @return The value, or null if the key was not found in this {@link ReadableKVState}.
     * @throws NullPointerException if the key is null.
     */
    @Nullable
    V get(@NonNull K key);

    /**
     * Used during migration ONLY. PLEASE DO NOT COME TO RELY ON THIS METHOD! It will be hopelessly
     * slow on large data sets like on disk!
     * After migrating to Mega Map, this method will be extremely slow.
     * DO NOT USE IT in production code.
     *
     * @return an iterator over all keys in the state
     */
    @NonNull
    Iterator<K> keys();

    /**
     * Gets the set of keys that a client read from the {@link ReadableKVState}.
     *
     * @return The possibly empty set of keys.
     */
    @NonNull
    Set<K> readKeys();

    /**
     * Gets the number of keys in the {@link ReadableKVState}.
     * Used only in tests.
     *
     * @deprecated DO NOT USE IT in production code as it returns size of all states, instead use {@code EntityIdService.entityCounts} to get the size of the state.
     *
     * @return number of keys in the {@link ReadableKVState}.
     */
    @Deprecated
    long size();

    /**
     * Warms the system by preloading an entity into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param key the key of the entity
     */
    default void warm(@NonNull final K key) {}
}
