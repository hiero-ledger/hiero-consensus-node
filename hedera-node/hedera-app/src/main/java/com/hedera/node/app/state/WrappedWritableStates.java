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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A wrapper around a {@link WritableStates} that provides a wrapped instances of {@link WritableKVState} and
 * {@link WritableSingletonState} of a given {@link WritableStates} delegate.
 */
public class WrappedWritableStates implements WritableStates {

    private WritableStates delegate;

    private final Map<Integer, WrappedWritableKVState<?, ?>> writableKVStateMap = new HashMap<>();
    private final Map<Integer, WrappedWritableSingletonState<?>> writableSingletonStateMap = new HashMap<>();
    private final Map<Integer, WrappedWritableQueueState<?>> writableQueueStateMap = new HashMap<>();

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
    public boolean contains(final int stateId) {
        return delegate.contains(stateId);
    }

    @Override
    @NonNull
    public Set<Integer> stateIds() {
        return delegate.stateIds();
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <K, V> WritableKVState<K, V> get(final int stateId) {
        final var cached = writableKVStateMap.get(stateId);
        if (cached != null) {
            return (WritableKVState<K, V>) cached;
        }
        final var created = new WrappedWritableKVState<K, V>(delegate.get(stateId));
        writableKVStateMap.put(stateId, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <T> WritableSingletonState<T> getSingleton(final int stateId) {
        final var cached = writableSingletonStateMap.get(stateId);
        if (cached != null) {
            return (WritableSingletonState<T>) cached;
        }
        final var created = new WrappedWritableSingletonState<T>(delegate.getSingleton(stateId));
        writableSingletonStateMap.put(stateId, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public <E> WritableQueueState<E> getQueue(final int stateId) {
        final var cached = writableQueueStateMap.get(stateId);
        if (cached != null) {
            return (WritableQueueState<E>) cached;
        }
        final var created = new WrappedWritableQueueState<E>(delegate.getQueue(stateId));
        writableQueueStateMap.put(stateId, created);
        return created;
    }

    /**
     * Rebinds cached wrappers to the current delegate instances and clears their buffers so this
     * object can be reused for another user dispatch.
     */
    public void reset() {
        for (final var entry : writableKVStateMap.entrySet()) {
            entry.getValue().retarget(delegate.get(entry.getKey()));
        }
        for (final var entry : writableSingletonStateMap.entrySet()) {
            entry.getValue().retarget(delegate.getSingleton(entry.getKey()));
        }
        for (final var entry : writableQueueStateMap.entrySet()) {
            entry.getValue().retarget(delegate.getQueue(entry.getKey()));
        }
    }

    /**
     * Points this wrapper at a new {@link WritableStates} and resets cached adapters.
     *
     * @param newDelegate the backend to wrap
     */
    public void retarget(@NonNull final WritableStates newDelegate) {
        this.delegate = requireNonNull(newDelegate);
        reset();
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
     */
    public void commit() {
        // Ensure all commits always happen in lexicographic order by state ID
        commitInStateIdOrder(writableKVStateMap, WrappedWritableKVState::commit);
        commitInStateIdOrder(writableQueueStateMap, WrappedWritableQueueState::commit);
        commitInStateIdOrder(writableSingletonStateMap, WrappedWritableSingletonState::commit);
        // In-memory backends must persist now. Merkle keeps write buffers until
        // VirtualMap.copy(); listeners already fired from wrap apply.
        if (delegate instanceof CommittableWritableStates terminalStates && terminalStates.requiresImmediateCommit()) {
            terminalStates.commit();
        }
    }

    private static <T> void commitInStateIdOrder(
            @NonNull final Map<Integer, T> instances, @NonNull final java.util.function.Consumer<T> commit) {
        final int n = instances.size();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            commit.accept(instances.values().iterator().next());
            return;
        }
        final int[] ids = new int[n];
        int i = 0;
        for (final int id : instances.keySet()) {
            ids[i++] = id;
        }
        Arrays.sort(ids);
        for (final int id : ids) {
            commit.accept(instances.get(id));
        }
    }
}
