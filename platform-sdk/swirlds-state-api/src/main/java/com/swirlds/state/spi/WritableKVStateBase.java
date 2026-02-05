// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * A base class for implementations of {@link WritableKVState}.
 *
 * @param <K> The key type
 * @param <V> The value type
 */
public abstract class WritableKVStateBase<K, V> extends ReadableKVStateBase<K, V> implements WritableKVState<K, V> {

    /** A map of all modified values buffered in this mutable state */
    private final Map<K, V> modifications;

    /** A list of listeners to be notified of changes to the state */
    private final List<KVChangeListener<K, V>> listeners = new ArrayList<>();

    /**
     * Create a new StateBase.
     *
     * @param stateId The state ID
     * @param label The state label
     */
    protected WritableKVStateBase(final int stateId, final String label) {
        this(stateId, label, new LinkedHashMap<>());
    }

    /**
     * Create a new StateBase from the provided map.
     *
     * @param stateId The state ID
     * @param label The state label
     * @param modifications A map that is used to init the cache.
     */
    protected WritableKVStateBase(
            final int stateId, @NonNull final String label, @NonNull final Map<K, V> modifications) {
        super(stateId, label);
        this.modifications = Objects.requireNonNull(modifications);
    }

    /**
     * Create a new StateBase from the provided map and read cache.
     *
     * @param stateId The state ID
     * @param label The state label
     * @param modifications A map that is used to init the cache.
     * @param readCache A concurrent map that is used to init the read cache.
     *
     *
     */
    // This constructor is used by some consumers of the API that are outside of this repository.
    @SuppressWarnings("unused")
    protected WritableKVStateBase(
            final int stateId,
            @NonNull final String label,
            @NonNull final Map<K, V> modifications,
            @NonNull ConcurrentMap<K, V> readCache) {
        super(stateId, label, readCache);
        this.modifications = Objects.requireNonNull(modifications);
    }

    /**
     * Register a listener to be notified of changes to the state on {@link #commit()}. We do not support unregistering
     * a listener, as the lifecycle of a {@link WritableKVState} is scoped to the set of mutations made to a state in a
     * round; and there is no use case where an application would only want to be notified of a subset of those changes.
     *
     * @param listener the listener to register
     */
    public void registerListener(@NonNull final KVChangeListener<K, V> listener) {
        requireNonNull(listener);
        listeners.add(listener);
    }

    /**
     * Flushes all changes into the underlying data store. This method should <strong>ONLY</strong>
     * be called by the code that created the {@link WritableKVStateBase} instance or owns it. Don't
     * cast and commit unless you own the instance!
     */
    public void commit() {
        for (final var entry : modifications.entrySet()) {
            final var key = entry.getKey();
            final var value = entry.getValue();
            if (value == null) {
                removeFromDataSource(key);
                listeners.forEach(listener -> listener.mapDeleteChange(key));
            } else {
                putIntoDataSource(key, value);
                listeners.forEach(listener -> listener.mapUpdateChange(key, value));
            }
        }
        reset();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clears the set of modified keys and removed keys. Equivalent semantically to a "rollback"
     * operation.
     */
    @Override
    public final void reset() {
        super.reset();
        modifications.clear();
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public final V get(@NonNull K key) {
        // If there is a modification, then we've already done a "put" or "remove"
        // and should return based on the modification
        if (modifications.containsKey(key)) {
            return modifications.get(key);
        } else {
            return super.get(key);
        }
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public V getOriginalValue(@NonNull K key) {
        return super.get(key);
    }

    /** {@inheritDoc} */
    @Override
    public final void put(@NonNull final K key, @NonNull final V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        modifications.put(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public final void remove(@NonNull final K key) {
        Objects.requireNonNull(key);
        modifications.put(key, null);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public final Set<K> modifiedKeys() {
        return modifications.keySet();
    }

    /**
     * {@inheritDoc}
     * For the size of a {@link WritableKVState}, we need to take into account the size of the
     * underlying data source, and the modifications that have been made to the state.
     * <ol>
     * <li>if the key is in backing store and is removed in modifications, then it is counted as removed</li>
     * <li>if the key is not in backing store and is added in modifications, then it is counted as addition</li>
     * <li>if the key is in backing store and is added in modifications, then it is not counted as the
     * key already exists in state</li>
     * <li>if the key is not in backing store and is being tried to be removed in modifications,
     * then it is not counted as the key does not exist in state.</li>
     * </ol>
     * @return The size of the state.
     */
    @Deprecated
    public long size() {
        final var sizeOfBackingMap = sizeOfDataSource();
        int numAdditions = 0;
        int numRemovals = 0;

        for (final var mod : modifications.entrySet()) {
            boolean isPresentInBackingMap = readFromDataSource(mod.getKey()) != null;
            boolean isRemovedInMod = mod.getValue() == null;

            if (isPresentInBackingMap && isRemovedInMod) {
                numRemovals++;
            } else if (!isPresentInBackingMap && !isRemovedInMod) {
                numAdditions++;
            }
        }
        return sizeOfBackingMap + numAdditions - numRemovals;
    }

    /**
     * Puts the given key/value pair into the underlying data source.
     *
     * @param key key to update
     * @param value value to put
     */
    protected abstract void putIntoDataSource(@NonNull K key, @NonNull V value);

    /**
     * Removes the given key and implicit value from the underlying data source.
     *
     * @param key key to remove from the underlying data source
     */
    protected abstract void removeFromDataSource(@NonNull K key);

    /**
     * Returns the size of the underlying data source. This can be a merkle map or a virtual map.
     * @return size of the underlying data source.
     */
    protected abstract long sizeOfDataSource();
}
