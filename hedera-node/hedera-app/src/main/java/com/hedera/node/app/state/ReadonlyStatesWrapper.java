// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A wrapper around a {@link WritableStates} that creates read-only versions of the underlying
 * writable state, which is needed in some scenarios to make changes visible.
 */
public class ReadonlyStatesWrapper implements ReadableStates {

    private final WritableStates delegate;
    private final Map<Integer, ReadableKVState<?, ?>> kvStates = new HashMap<>();
    private final Map<Integer, ReadableSingletonState<?>> singletonStates = new HashMap<>();
    private final Map<Integer, ReadableQueueState<?>> queueStates = new HashMap<>();

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
    @SuppressWarnings("unchecked")
    public <K, V> ReadableKVState<K, V> get(final int stateId) {
        final var cached = kvStates.get(stateId);
        if (cached != null) {
            return (ReadableKVState<K, V>) cached;
        }
        final var created = new ReadonlyKVStateWrapper<K, V>(delegate.get(stateId));
        kvStates.put(stateId, created);
        return created;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T> ReadableSingletonState<T> getSingleton(final int stateId) {
        final var cached = singletonStates.get(stateId);
        if (cached != null) {
            return (ReadableSingletonState<T>) cached;
        }
        final var created = new ReadonlySingletonStateWrapper<T>(delegate.getSingleton(stateId));
        singletonStates.put(stateId, created);
        return created;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <E> ReadableQueueState<E> getQueue(final int stateId) {
        final var cached = queueStates.get(stateId);
        if (cached != null) {
            return (ReadableQueueState<E>) cached;
        }
        final var created = new ReadonlyQueueStateWrapper<E>(delegate.getQueue(stateId));
        queueStates.put(stateId, created);
        return created;
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
