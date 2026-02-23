// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An implementation of {@link WritableStates} that is useful for testing purposes and creates
 * {@link Map}-based states such as {@link MapWritableKVState}.
 *
 * <p>A convenient {@link Builder} is provided to define the set of states available.
 */
@SuppressWarnings("rawtypes")
public class MapWritableStates implements WritableStates, CommittableWritableStates {

    private final Map<Integer, ?> states;

    /**
     * Need this callback so a {@code FakeHederaState} owning this writable states can
     * invalidate its cache of {@link com.swirlds.state.spi.ReadableStates} when it is
     * committed.
     */
    @Nullable
    private final Runnable onCommit;

    public MapWritableStates(@NonNull final Map<Integer, ?> states) {
        this(states, null);
    }

    public MapWritableStates(@NonNull final Map<Integer, ?> states, @Nullable final Runnable onCommit) {
        this.states = requireNonNull(states);
        this.onCommit = onCommit;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <K, V> WritableKVState<K, V> get(final int stateId) {
        final var state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown K/V state ID " + stateId);
        }

        return (WritableKVState<K, V>) state;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> WritableSingletonState<T> getSingleton(final int stateId) {
        final var state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown singleton state ID " + stateId);
        }

        return (WritableSingletonState<T>) state;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <E> WritableQueueState<E> getQueue(final int stateId) {
        final var state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown queue state ID " + stateId);
        }

        return (WritableQueueState<E>) state;
    }

    @Override
    public boolean contains(final int stateId) {
        return states.containsKey(stateId);
    }

    @NonNull
    @Override
    public Set<Integer> stateIds() {
        return Collections.unmodifiableSet(states.keySet());
    }

    @Override
    public int size() {
        return states.size();
    }

    @Override
    public void commit() {
        states.values().forEach(state -> {
            if (state instanceof WritableKVStateBase kv) {
                kv.commit();
            } else if (state instanceof WritableSingletonStateBase singleton) {
                singleton.commit();
            } else if (state instanceof WritableQueueStateBase queue) {
                queue.commit();
            } else {
                throw new IllegalStateException(
                        "Unknown state type " + state.getClass().getName());
            }
        });
        if (onCommit != null) {
            onCommit.run();
        }
    }

    @Override
    public String toString() {
        return "MapWritableStates{" + "states=" + states + '}';
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return Gets a new {@link Builder} to use for creating a {@link MapWritableStates} instance.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /** A convenience builder */
    public static final class Builder {

        private final Map<Integer, Object> states = new HashMap<>();

        /**
         * Defines a new {@link MapWritableKVState} that should be available in the {@link
         * MapWritableStates} instance created by this builder.
         *
         * @param state The state to include
         * @return a reference to this builder
         */
        @NonNull
        public Builder state(@NonNull final MapWritableKVState state) {
            this.states.put(state.getStateId(), state);
            return this;
        }

        /**
         * Defines a new {@link WritableSingletonState} that should be available in the {@link
         * MapReadableStates} instance created by this builder.
         *
         * @param state The state to include
         * @return a reference to this builder
         */
        @NonNull
        public Builder state(@NonNull final WritableSingletonState<?> state) {
            this.states.put(state.getStateId(), state);
            return this;
        }

        /**
         * Defines a new {@link WritableQueueState} that should be available in the {@link
         * MapReadableStates} instance created by this builder.
         *
         * @param state The state to include
         * @return a reference to this builder
         */
        @NonNull
        public Builder state(@NonNull final WritableQueueState<?> state) {
            this.states.put(state.getStateId(), state);
            return this;
        }

        /**
         * Creates and returns a new {@link MapWritableStates} instance based on the states defined
         * in this builder.
         *
         * @return The instance
         */
        @NonNull
        public MapWritableStates build() {
            return new MapWritableStates(new HashMap<>(states));
        }
    }
}
