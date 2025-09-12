// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableKVStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A simple implementation of {@link ReadableKVState} backed by a
 * {@link Map}. Test code has the option of creating an instance disregarding the backing map, or by
 * supplying the backing map to use. This latter option is useful if you want to use Mockito to spy
 * on it, or if you want to pre-populate it, or use Mockito to make the map throw an exception in
 * some strange case, or in some other way work with the backing map directly.
 *
 * <p>A convenient {@link Builder} is provided to create the map (since there are no map literals in
 * Java). The {@link #builder(String, int)} method can be used to create the builder.
 *
 * @param <K> The key type
 * @param <V> The value type
 */
public class MapReadableKVState<K, V> extends ReadableKVStateBase<K, V> {

    /** Represents the backing storage for this state */
    private final Map<K, V> backingStore;

    /**
     * Create an instance using the given map as the backing store. This is useful when you want to
     * pre-populate the map, or if you want to use Mockito to mock it or cause it to throw
     * exceptions when certain keys are accessed, etc.
     *
     * @param stateId      The state ID for this state
     * @param label        The service label
     * @param backingStore The backing store to use
     */
    public MapReadableKVState(final int stateId, final String label, @NonNull final Map<K, V> backingStore) {
        super(stateId, label);
        this.backingStore = Objects.requireNonNull(backingStore);
    }

    @Override
    protected V readFromDataSource(@NonNull K key) {
        return backingStore.get(key);
    }

    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        return backingStore.keySet().iterator();
    }

    /** {@inheritDoc} */
    @Override
    public long size() {
        return backingStore.size();
    }

    /**
     * Create a new {@link Builder} for building a {@link MapReadableKVState}. The builder has
     * convenience methods for pre-populating the map.
     *
     * @param <K>         The key type
     * @param <V>         The value type
     * @param stateId     The state ID
     * @param label       The state label
     * @return A {@link Builder} to be used for creating a {@link MapReadableKVState}.
     */
    @NonNull
    public static <K, V> Builder<K, V> builder(final int stateId, @NonNull final String label) {
        return new Builder<>(stateId, label);
    }

    /**
     * A convenient builder for creating instances of {@link
     * MapReadableKVState}.
     */
    public static final class Builder<K, V> {

        private final int stateId;
        private final String label;
        private final Map<K, V> backingStore = new HashMap<>();

        Builder(final int stateId, final String label) {
            this.stateId = stateId;
            this.label = label;
        }

        /**
         * Add a key/value pair to the state's backing map. This is used to pre-initialize the
         * backing map. The created state will be "clean" with no modifications.
         *
         * @param key The key
         * @param value The value
         * @return a reference to this builder
         */
        @NonNull
        public Builder<K, V> value(@NonNull K key, @Nullable V value) {
            backingStore.put(key, value);
            return this;
        }

        /**
         * Builds the state.
         *
         * @return an instance of the state, preloaded with whatever key-value pairs were defined.
         */
        @NonNull
        public MapReadableKVState<K, V> build() {
            return new MapReadableKVState<>(stateId, label, new HashMap<>(backingStore));
        }
    }
}
