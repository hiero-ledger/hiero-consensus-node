// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.*;

/**
 * An implementation of {@link ReadableStates} that is useful for testing purposes and creates
 * {@link Map}-based states such as {@link MapReadableKVState}.
 *
 * <p>A convenient {@link Builder} is provided to define the set of states available.
 */
@SuppressWarnings("rawtypes")
public class MapReadableStates implements ReadableStates {

    private final Map<Integer, ?> states;

    public MapReadableStates(@NonNull final Map<Integer, ?> states) {
        this.states = Objects.requireNonNull(states);
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <K, V> ReadableKVState<K, V> get(final int stateId) {
        final var state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown K/V state ID " + stateId);
        }

        return (ReadableKVState<K, V>) state;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T> ReadableSingletonState<T> getSingleton(final int stateId) {
        final var state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown singleton state ID " + stateId);
        }

        return (ReadableSingletonState<T>) state;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <E> ReadableQueueState<E> getQueue(final int stateId) {
        final var state = states.get(stateId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown queue state ID " + stateId);
        }

        return (ReadableQueueState<E>) state;
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

    /**
     * Creates a new {@link Builder}.
     *
     * @return Gets a new {@link Builder} to use for creating a {@link MapReadableStates} instance.
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /** A convenience builder */
    public static final class Builder {

        private final Map<Integer, Object> states = new HashMap<>();

        Builder() {}

        /**
         * Defines a new {@link MapReadableKVState} that should be available in the {@link
         * MapReadableStates} instance created by this builder.
         *
         * @param state The state to include
         * @return a reference to this builder
         */
        @NonNull
        public Builder state(@NonNull final MapReadableKVState state) {
            this.states.put(state.getStateId(), state);
            return this;
        }

        /**
         * Defines a new {@link ReadableSingletonState} that should be available in the {@link
         * MapReadableStates} instance created by this builder.
         *
         * @param state The state to include
         * @return a reference to this builder
         */
        @NonNull
        public Builder state(@NonNull final ReadableSingletonState<?> state) {
            this.states.put(state.getStateId(), state);
            return this;
        }

        /**
         * Defines a new {@link ReadableQueueState} that should be available in the {@link
         * MapReadableStates} instance created by this builder.
         *
         * @param state The state to include
         * @return a reference to this builder
         */
        @NonNull
        public Builder state(@NonNull final ReadableQueueState<?> state) {
            this.states.put(state.getStateId(), state);
            return this;
        }

        /**
         * Creates and returns a new {@link MapReadableStates} instance based on the states defined
         * in this builder.
         *
         * @return The instance
         */
        @NonNull
        public MapReadableStates build() {
            return new MapReadableStates(new HashMap<>(states));
        }
    }
}
