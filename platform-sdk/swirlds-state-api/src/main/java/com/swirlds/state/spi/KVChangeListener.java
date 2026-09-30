// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A listener that is notified when a key-value pair is added to or removed from a map. Note that
 * {@link WritableKVState} implementations do not support null values, so neither does this listener.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public interface KVChangeListener<K, V> {
    /** Whether to capture the prior value before committing an update. */
    default boolean requiresPreviousValue() {
        return false;
    }

    /** Receives the previous committed value, or null for an insertion. */
    default void mapUpdateChange(@NonNull K key, @Nullable V previousValue, @NonNull V value) {
        mapUpdateChange(key, value);
    }

    /** Receives the original next value and its equal, untracked instance committed to storage. */
    default void mapUpdateChange(@NonNull K key, @Nullable V previousValue, @NonNull V value, @NonNull V storedValue) {
        mapUpdateChange(key, previousValue, value);
    }

    /**
     * Called when an entry is added in to a map.
     *
     * @param key The key added to the map
     * @param value The value added to the map
     */
    void mapUpdateChange(@NonNull K key, @NonNull V value);

    /**
     * Called when an entry is removed from a map.
     *
     * @param key The key removed from the map
     */
    void mapDeleteChange(@NonNull K key);
}
