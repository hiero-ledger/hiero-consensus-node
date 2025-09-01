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
    private final Set<Integer> stateIds;

    /**
     * Create a new instance.
     *
     * @param delegate The instance to delegate to
     * @param stateIds The set of state IDs in {@code delegate} to expose
     */
    public FilteredReadableStates(@NonNull final ReadableStates delegate, @NonNull final Set<Integer> stateIds) {
        this.delegate = Objects.requireNonNull(delegate);

        // Only include those state keys that are actually in the underlying delegate
        this.stateIds = stateIds.stream().filter(delegate::contains).collect(Collectors.toUnmodifiableSet());
    }

    @NonNull
    @Override
    public <K, V> ReadableKVState<K, V> get(final int stateId) {
        Objects.requireNonNull(stateId);
        if (!contains(stateId)) {
            throw new IllegalArgumentException("Could not find k/v state " + stateId);
        }

        return delegate.get(stateId);
    }

    @NonNull
    @Override
    public <T> ReadableSingletonState<T> getSingleton(final int stateId) {
        Objects.requireNonNull(stateId);
        if (!contains(stateId)) {
            throw new IllegalArgumentException("Could not find singleton state " + stateId);
        }

        return delegate.getSingleton(stateId);
    }

    @NonNull
    @Override
    public <E> ReadableQueueState<E> getQueue(final int stateId) {
        Objects.requireNonNull(stateId);
        if (!contains(stateId)) {
            throw new IllegalArgumentException("Could not find queue state " + stateId);
        }

        return delegate.getQueue(stateId);
    }

    @Override
    public boolean contains(final int stateId) {
        return stateIds.contains(stateId);
    }

    @NonNull
    @Override
    public Set<Integer> stateIds() {
        return stateIds;
    }
}
