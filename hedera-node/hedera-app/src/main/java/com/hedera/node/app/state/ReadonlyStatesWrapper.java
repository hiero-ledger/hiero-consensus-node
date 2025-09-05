// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * A wrapper around a {@link WritableStates} that creates read-only versions of the underlying
 * writable state, which is needed in some scenarios to make changes visible.
 */
public class ReadonlyStatesWrapper implements ReadableStates {

    private final WritableStates delegate;

    /**
     * Create a new wrapper around the given {@code delegate}.
     *
     * @param delegate the {@link WritableStates} to wrap
     */
    public ReadonlyStatesWrapper(@NonNull final WritableStates delegate) {
        this.delegate = delegate;
    }

    @NonNull
    @Override
    public <K, V> ReadableKVState<K, V> get(final int stateId) {
        return new ReadonlyKVStateWrapper<>(delegate.get(stateId));
    }

    @NonNull
    @Override
    public <T> ReadableSingletonState<T> getSingleton(final int stateId) {
        return new ReadonlySingletonStateWrapper<>(delegate.getSingleton(stateId));
    }

    @NonNull
    @Override
    public <E> ReadableQueueState<E> getQueue(final int stateId) {
        return new ReadonlyQueueStateWrapper<>(delegate.getQueue(stateId));
    }

    @Override
    public boolean contains(final int stateId) {
        return delegate.contains(stateId);
    }

    @NonNull
    @Override
    public Set<Integer> stateIds() {
        return delegate.stateIds();
    }
}
