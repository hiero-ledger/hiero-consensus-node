// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;

/**
 * An implementation of {@link WritableStates} that delegates to another instance, and filters the
 * available set of states.
 */
public class FilteredWritableStates extends FilteredReadableStates implements WritableStates {

    /** The {@link WritableStates} to delegate to */
    private final WritableStates delegate;

    /**
     * Create a new instance.
     *
     * @param delegate The instance to delegate to
     * @param stateIds The set of state IDs in {@code delegate} to expose
     */
    public FilteredWritableStates(@NonNull final WritableStates delegate, @NonNull final Set<Integer> stateIds) {
        super(delegate, stateIds);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @NonNull
    @Override
    public <K, V> WritableKVState<K, V> get(final int stateId) {
        if (!contains(stateId)) {
            throw new IllegalArgumentException("Could not find k/v state ID " + stateId);
        }

        return delegate.get(stateId);
    }

    @NonNull
    @Override
    public <T> WritableSingletonState<T> getSingleton(final int stateId) {
        if (!contains(stateId)) {
            throw new IllegalArgumentException("Could not find singleton state ID " + stateId);
        }

        return delegate.getSingleton(stateId);
    }

    @NonNull
    @Override
    public <E> WritableQueueState<E> getQueue(final int stateId) {
        if (!contains(stateId)) {
            throw new IllegalArgumentException("Could not find queue state ID " + stateId);
        }

        return delegate.getQueue(stateId);
    }

    public WritableStates getDelegate() {
        return delegate;
    }
}
