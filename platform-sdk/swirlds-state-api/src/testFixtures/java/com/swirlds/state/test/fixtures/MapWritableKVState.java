// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.swirlds.state.spi.WritableKVStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A simple implementation of {@link com.swirlds.state.spi.WritableKVState} backed by a
 * {@link Map}. Test code has the option of creating an instance disregarding the backing map, or by
 * supplying the backing map to use. This latter option is useful if you want to use Mockito to spy
 * on it, or if you want to pre-populate it, or use Mockito to make the map throw an exception in
 * some strange case, or in some other way work with the backing map directly.
 *
 * <p>A convenient {@link Builder} is provided to create the map (since there are no map literals in
 * Java). The {@link #builder(String, String)} method can be used to create the builder.
 *
 * @param <K> The key type
 * @param <V> The value type
 */
public class MapWritableKVState<K, V> extends WritableKVStateBase<K, V> {
    /**
     * Represents the backing storage for this state
     */
    private final Map<K, V> backingStore;

    /**
     * Create an instance using a HashMap as the backing store.
     *
     * @param stateKey The state key for this state
     */
    public MapWritableKVState(@NonNull final String serviceName, @NonNull final String stateKey) {
        this(serviceName, stateKey, new HashMap<>());
    }

    /**
     * Create an instance using the given map as the backing store. This is useful when you want to
     * pre-populate the map, or if you want to use Mockito to mock it or cause it to throw
     * exceptions when certain keys are accessed, etc.
     *
     * @param serviceName  The service name
     * @param stateKey     The state key for this state
     * @param backingStore The backing store to use
     */
    public MapWritableKVState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final Map<K, V> backingStore) {
        super(serviceName, stateKey);
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

    @Override
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        backingStore.put(key, value);
    }

    @Override
    protected void removeFromDataSource(@NonNull K key) {
        backingStore.remove(key);
    }

    /**
     * Get the backing store for this state. This is only for testing purposes added to {@link MapWritableKVState}
     *
     * @return The backing store for this state
     */
    public Map<K, V> getBackingStore() {
        return backingStore;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public long sizeOfDataSource() {
        return backingStore.size();
    }

    @Override
    public String toString() {
        return "MapWritableKVState{" + "backingStore=" + backingStore + '}';
    }

    /**
     * Create a new {@link Builder} for building a {@link MapWritableKVState}. The builder has
     * convenience methods for pre-populating the map.
     *
     * @param serviceName The service name
     * @param stateKey    The state key
     * @param <K>      The key type
     * @param <V>      The value type
     * @return A {@link Builder} to be used for creating a {@link MapWritableKVState}.
     */
    public static <K, V> Builder<K, V> builder(@NonNull final String serviceName, @NonNull final String stateKey) {
        return new Builder<>(serviceName, stateKey);
    }

    /**
     * A convenient builder for creating instances of {@link
     * MapWritableKVState}.
     */
    public static final class Builder<K, V> {
        private final Map<K, V> backingStore = new HashMap<>();
        private final String serviceName;
        private final String stateKey;

        public Builder(@NonNull final String serviceName, @NonNull final String stateKey) {
            this.serviceName = serviceName;
            this.stateKey = stateKey;
        }

        /**
         * Add a key/value pair to the state's backing map. This is used to pre-initialize the
         * backing map. The created state will be "clean" with no modifications.
         *
         * @param key   The key
         * @param value The value
         * @return a reference to this builder
         */
        public Builder<K, V> value(@NonNull K key, @Nullable V value) {
            backingStore.put(key, value);
            return this;
        }

        /**
         * Builds the state.
         *
         * @return an instance of the state, preloaded with whatever key-value pairs were defined.
         */
        public MapWritableKVState<K, V> build() {
            return new MapWritableKVState<>(serviceName, stateKey, new HashMap<>(backingStore));
        }
    }
}
