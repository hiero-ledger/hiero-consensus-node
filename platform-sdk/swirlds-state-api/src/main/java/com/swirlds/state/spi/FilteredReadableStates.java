// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An implementation of {@link ReadableStates} that delegates to another instance, and filters the
 * available set of states.
 */
public class FilteredReadableStates implements ReadableStates {
    /** The {@link ReadableStates} to delegate to */
    private final ReadableStates delegate;
    /** The set of states to honor in {@link #delegate}. */
    private final Set<String> stateKeys;

    /**
     * Create a new instance.
     *
     * @param delegate The instance to delegate to
     * @param stateKeys The set of keys in {@code delegate} to expose
     */
    public FilteredReadableStates(@NonNull final ReadableStates delegate, @NonNull final Set<String> stateKeys) {
        this.delegate = Objects.requireNonNull(delegate);

        // Only include those state keys that are actually in the underlying delegate
        this.stateKeys = stateKeys.stream().filter(delegate::contains).collect(Collectors.toUnmodifiableSet());
    }

    @NonNull
    @Override
    public <K, V> ReadableKVState<K, V> get(@NonNull String stateKey) {
        Objects.requireNonNull(stateKey);
        if (!contains(stateKey)) {
            throw new IllegalArgumentException("Could not find k/v state " + stateKey);
        }

        return delegate.get(stateKey);
    }

    @NonNull
    @Override
    public <T> ReadableSingletonState<T> getSingleton(@NonNull String stateKey) {
        Objects.requireNonNull(stateKey);
        if (!contains(stateKey)) {
            throw new IllegalArgumentException("Could not find singleton state " + stateKey);
        }

        return delegate.getSingleton(stateKey);
    }

    @NonNull
    @Override
    public <E> ReadableQueueState<E> getQueue(@NonNull String stateKey) {
        Objects.requireNonNull(stateKey);
        if (!contains(stateKey)) {
            throw new IllegalArgumentException("Could not find queue state " + stateKey);
        }

        return delegate.getQueue(stateKey);
    }

    @Override
    public boolean contains(@NonNull String stateKey) {
        return stateKeys.contains(stateKey);
    }

    @NonNull
    @Override
    public Set<String> stateKeys() {
        return stateKeys;
    }
}
