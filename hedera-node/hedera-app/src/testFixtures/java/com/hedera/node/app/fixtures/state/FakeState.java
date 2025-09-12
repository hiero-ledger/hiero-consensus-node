// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.StateChangeListener.StateType.SINGLETON;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.HederaStateRoot;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.spi.EmptyReadableStates;
import com.swirlds.state.spi.EmptyWritableStates;
import com.swirlds.state.spi.KVChangeListener;
import com.swirlds.state.spi.QueueChangeListener;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.ListReadableQueueState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.Hash;

/**
 * A useful test double for {@link State}. Works together with {@link MapReadableStates} and other fixtures.
 */
@ConstructableIgnored
public class FakeState implements MerkleNodeState {

    // Key is Service, value is Map of state name to HashMap or List or Object (depending on state type)
    private final Map<String, Map<Integer, Object>> states = new ConcurrentHashMap<>();
    private final Map<String, ReadableStates> readableStates = new ConcurrentHashMap<>();
    private final Map<String, WritableStates> writableStates = new ConcurrentHashMap<>();
    /**
     * Listeners to be notified of state changes on {@link MapWritableStates#commit()} calls for any service.
     */
    private final List<StateChangeListener> listeners = new ArrayList<>();

    /**
     * Exposes the underlying states for direct manipulation in tests.
     *
     * @return the states
     */
    public Map<String, Map<Integer, Object>> getStates() {
        return states;
    }

    @Override
    public MerkleNode getRoot() {
        return new HederaStateRoot().getRoot();
    }

    /**
     * Adds to the service with the given name the {@link ReadableKVState} {@code states}
     */
    public FakeState addService(@NonNull final String serviceName, @NonNull final Map<Integer, ?> dataSources) {
        final var serviceStates = this.states.computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>());
        dataSources.forEach((k, b) -> {
            if (!serviceStates.containsKey(k)) {
                serviceStates.put(k, b);
            }
        });
        // Purge any readable or writable states whose state definitions are now stale,
        // since they don't include the new data sources we just added
        purgeStatesCaches(serviceName);
        return this;
    }

    /**
     * Removes the state with the given key for the service with the given name.
     *
     * @param serviceName the name of the service
     * @param stateId     the ID of the state
     */
    public void removeServiceState(@NonNull final String serviceName, final int stateId) {
        requireNonNull(serviceName);
        this.states.computeIfPresent(serviceName, (k, v) -> {
            v.remove(stateId);
            return v;
        });
        purgeStatesCaches(serviceName);
    }

    @NonNull
    @Override
    @SuppressWarnings("java:S3740") // provide the parameterized type for the generic state variable
    public ReadableStates getReadableStates(@NonNull String serviceName) {
        return readableStates.computeIfAbsent(serviceName, s -> {
            final var serviceStates = this.states.get(s);
            if (serviceStates == null) {
                return new EmptyReadableStates();
            }
            final Map<Integer, Object> states = new ConcurrentHashMap<>();
            for (final var entry : serviceStates.entrySet()) {
                final var stateId = entry.getKey();
                final var state = entry.getValue();
                if (state instanceof Queue queue) {
                    states.put(stateId, new ListReadableQueueState(stateId, serviceName + "." + stateId, queue));
                } else if (state instanceof Map map) {
                    states.put(stateId, new MapReadableKVState(stateId, serviceName + "." + stateId, map));
                } else if (state instanceof AtomicReference ref) {
                    states.put(
                            stateId,
                            new FunctionReadableSingletonState(stateId, serviceName + "." + stateId, ref::get));
                }
            }
            return new MapReadableStates(states);
        });
    }

    @Override
    @NonNull
    public WritableStates getWritableStates(@NonNull final String serviceName) {
        requireNonNull(serviceName);
        return writableStates.computeIfAbsent(serviceName, s -> {
            final var serviceStates = states.get(s);
            if (serviceStates == null) {
                return new EmptyWritableStates();
            }
            final Map<Integer, Object> data = new ConcurrentHashMap<>();
            for (final var entry : serviceStates.entrySet()) {
                final var stateId = entry.getKey();
                final var state = entry.getValue();
                if (state instanceof Queue<?> queue) {
                    data.put(
                            stateId,
                            withAnyRegisteredListeners(
                                    serviceName,
                                    new ListWritableQueueState<>(stateId, serviceName + "." + stateId, queue)));
                } else if (state instanceof Map<?, ?> map) {
                    data.put(
                            stateId,
                            withAnyRegisteredListeners(
                                    serviceName, new MapWritableKVState<>(stateId, serviceName + "." + stateId, map)));
                } else if (state instanceof AtomicReference<?> ref) {
                    data.put(stateId, withAnyRegisteredListeners(serviceName, stateId, ref));
                }
            }
            return new MapWritableStates(data, () -> readableStates.remove(serviceName));
        });
    }

    @Override
    public void registerCommitListener(@NonNull final StateChangeListener listener) {
        requireNonNull(listener);
        listeners.add(listener);
    }

    @Override
    public void unregisterCommitListener(@NonNull final StateChangeListener listener) {
        requireNonNull(listener);
        listeners.remove(listener);
    }

    /**
     * Commits all pending changes made to the states.
     */
    public void commit() {
        writableStates.values().forEach(writableStates -> {
            if (writableStates instanceof MapWritableStates mapWritableStates) {
                mapWritableStates.commit();
            }
        });
    }

    private <V> WritableSingletonStateBase<V> withAnyRegisteredListeners(
            @NonNull final String serviceName, final int stateId, @NonNull final AtomicReference<V> ref) {
        final var state =
                new FunctionWritableSingletonState<>(stateId, serviceName + "." + stateId, ref::get, ref::set);
        listeners.forEach(listener -> {
            if (listener.stateTypes().contains(SINGLETON)) {
                registerSingletonListener(state, listener);
            }
        });
        return state;
    }

    private <K, V> MapWritableKVState<K, V> withAnyRegisteredListeners(
            @NonNull final String serviceName, @NonNull final MapWritableKVState<K, V> state) {
        listeners.forEach(listener -> {
            if (listener.stateTypes().contains(MAP)) {
                registerKVListener(state, listener);
            }
        });
        return state;
    }

    private <T> ListWritableQueueState<T> withAnyRegisteredListeners(
            @NonNull final String serviceName, @NonNull final ListWritableQueueState<T> state) {
        listeners.forEach(listener -> {
            if (listener.stateTypes().contains(QUEUE)) {
                registerQueueListener(state, listener);
            }
        });
        return state;
    }

    private <V> void registerSingletonListener(
            @NonNull final WritableSingletonStateBase<V> singletonState, @NonNull final StateChangeListener listener) {
        final var stateId = singletonState.getStateId();
        singletonState.registerListener(value -> listener.singletonUpdateChange(stateId, value));
    }

    private <V> void registerQueueListener(
            @NonNull final WritableQueueStateBase<V> queueState, @NonNull final StateChangeListener listener) {
        final var stateId = queueState.getStateId();
        queueState.registerListener(new QueueChangeListener<>() {
            @Override
            public void queuePushChange(@NonNull final V value) {
                listener.queuePushChange(stateId, value);
            }

            @Override
            public void queuePopChange() {
                listener.queuePopChange(stateId);
            }
        });
    }

    private <K, V> void registerKVListener(WritableKVStateBase<K, V> state, StateChangeListener listener) {
        final var stateId = state.getStateId();
        state.registerListener(new KVChangeListener<>() {
            @Override
            public void mapUpdateChange(@NonNull final K key, @NonNull final V value) {
                listener.mapUpdateChange(stateId, key, value);
            }

            @Override
            public void mapDeleteChange(@NonNull final K key) {
                listener.mapDeleteChange(stateId, key);
            }
        });
    }

    private void purgeStatesCaches(@NonNull final String serviceName) {
        readableStates.remove(serviceName);
        writableStates.remove(serviceName);
    }

    @Override
    public void init(
            Time time,
            Configuration configuration,
            Metrics metrics,
            MerkleCryptography merkleCryptography,
            LongSupplier roundSupplier) {
        // no-op
    }

    @Override
    public void setHash(Hash hash) {
        // no-op
    }

    @Override
    public @NonNull MerkleNodeState copy() {
        return this;
    }

    @Override
    public <T extends MerkleNode> void putServiceStateIfAbsent(
            @NonNull StateMetadata<?, ?> md, @NonNull Supplier<T> nodeSupplier, @NonNull Consumer<T> nodeInitializer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterService(@NonNull String serviceName) {
        throw new UnsupportedOperationException();
    }
}
