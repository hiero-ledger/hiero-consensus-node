// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A listener that is notified when a key-value pair is added to or removed from a map. Note that
 * {@link WritableKVState} implementations do not support null values, so neither does this listener.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public interface KVChangeListener<K, V> {
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
