// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Set;

/**
 * A mutable state.
 *
 * @param <K> The key, which must be of the appropriate kind depending on whether it is stored in
 *     memory, or on disk.
 * @param <V> The value, which must be of the appropriate kind depending on whether it is stored in
 *     memory, or on disk.
 */
public interface WritableKVState<K, V> extends ReadableKVState<K, V> {

    /**
     * Gets the original value associated with the given key before any modifications were made to
     * it. The returned value will be {@code null} if the key does not exist.
     *
     * @param key The key. Cannot be null, otherwise an exception is thrown.
     * @return The original value, or null if there is no such key in the state
     * @throws NullPointerException if the key is null.
     */
    @Nullable
    V getOriginalValue(@NonNull K key);

    /**
     * Adds a new value to the store, or updates an existing value.
     *
     * @param key The key. Cannot be null.
     * @param value The value. Cannot be null.
     * @throws NullPointerException if the key or value is null.
     */
    void put(@NonNull K key, @NonNull V value);

    /**
     * Removes the given key and its associated value from the map. Subsequent calls to {@link
     * #contains} with the given key will return false, and subsequent calls to {@link
     * #get} will return {@code null}.
     *
     * @param key The key representing the key/value to remove. Cannot be null.
     * @throws NullPointerException if the key or value is null.
     */
    void remove(@NonNull K key);

    /**
     * {@inheritDoc}
     *
     * <p>When used on a {@link WritableKVState}, this iterator will include new keys added to the
     * state but not yet committed, and omit keys that have been removed but not yet committed, and
     * of course whatever the committed backend state is.
     *
     * <p>If an iterator is created, and then a change is made to this state, then the changes will
     * not be reflected in the iterator. If an iterator is created, and then the state is committed,
     * further operations on the iterator may succeed, or may throw {@link
     * java.util.ConcurrentModificationException}, depending on the behavior of the backing store.
     */
    @Override
    @NonNull
    Iterator<K> keys();

    /**
     * Gets a {@link Set} of all keys that have been modified through this {@link WritableKVState}.
     * Keys used with {@link #put(K, V)} and {@link #remove(K)} are included in this set.
     *
     * @return A non-null set of modified keys, which may be empty.
     */
    @NonNull
    Set<K> modifiedKeys();

    /**
     * Returns {@code true} if this {@link WritableKVState} has been modified since it was created
     *
     * @return {@code true} if this {@link WritableKVState} has been modified since it was created
     */
    default boolean isModified() {
        return !modifiedKeys().isEmpty();
    }
}
