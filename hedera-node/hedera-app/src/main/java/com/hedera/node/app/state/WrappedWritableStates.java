// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WrappedWritableKVState;
import com.swirlds.state.spi.WrappedWritableQueueState;
import com.swirlds.state.spi.WrappedWritableSingletonState;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A wrapper around a {@link WritableStates} that provides a wrapped instances of {@link WritableKVState} and
 * {@link WritableSingletonState} of a given {@link WritableStates} delegate.
 */
public class WrappedWritableStates implements WritableStates {

    private final WritableStates delegate;
    private final Map<String, WrappedWritableKVState<?, ?>> writableKVStateMap = new HashMap<>();
    private final Map<String, WrappedWritableSingletonState<?>> writableSingletonStateMap = new HashMap<>();
    private final Map<String, WrappedWritableQueueState<?>> writableQueueStateMap = new HashMap<>();

    /**
     * Constructs a {@link WrappedWritableStates} that wraps the given {@link WritableStates}.
     *
     * @param delegate the {@link WritableStates} to wrap
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public WrappedWritableStates(@NonNull final WritableStates delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public boolean contains(@NonNull String stateKey) {
        return delegate.contains(stateKey);
    }

    @Override
    @NonNull
    public Set<String> stateKeys() {
        return delegate.stateKeys();
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <K, V> WritableKVState<K, V> get(@NonNull String stateKey) {
        return (WritableKVState<K, V>)
                writableKVStateMap.computeIfAbsent(stateKey, s -> new WrappedWritableKVState<>(delegate.get(stateKey)));
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <T> WritableSingletonState<T> getSingleton(@NonNull String stateKey) {
        return (WritableSingletonState<T>) writableSingletonStateMap.computeIfAbsent(
                stateKey, s -> new WrappedWritableSingletonState<>(delegate.getSingleton(stateKey)));
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <E> WritableQueueState<E> getQueue(@NonNull String stateKey) {
        return (WritableQueueState<E>) writableQueueStateMap.computeIfAbsent(
                stateKey, s -> new WrappedWritableQueueState<>(delegate.getQueue(stateKey)));
    }

    /**
     * Returns {@code true} if the state of this {@link WrappedWritableStates} has been modified.
     *
     * @return {@code true}, if the state has been modified; otherwise {@code false}
     */
    public boolean isModified() {
        for (WrappedWritableKVState<?, ?> kvState : writableKVStateMap.values()) {
            if (kvState.isModified()) {
                return true;
            }
        }
        for (WrappedWritableQueueState<?> queueState : writableQueueStateMap.values()) {
            if (queueState.isModified()) {
                return true;
            }
        }
        for (WrappedWritableSingletonState<?> singletonState : writableSingletonStateMap.values()) {
            if (singletonState.isModified()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes all modifications to the underlying {@link WritableStates}.
     *
     * @param commitSingletons if {@code true} commits singleton states.
     */
    public void commit(boolean commitSingletons) {
        for (WrappedWritableKVState<?, ?> kvState : writableKVStateMap.values()) {
            kvState.commit();
        }
        for (WrappedWritableQueueState<?> queueState : writableQueueStateMap.values()) {
            queueState.commit();
        }
        if (commitSingletons) {
            for (WrappedWritableSingletonState<?> singletonState : writableSingletonStateMap.values()) {
                singletonState.commit();
            }
        }

        if (delegate instanceof CommittableWritableStates terminalStates) {
            terminalStates.commit();
        }
    }
}
