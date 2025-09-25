// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines a listener to be notified of changes made to the {@link com.swirlds.state.spi.WritableStates} returned by
 * a {@link State}. In general, the only {@link State} implementations that will support registering listeners are those
 * that return {@link com.swirlds.state.spi.WritableStates} marked as {@link CommittableWritableStates}.
 * <p>
 * A listener is registered with a {@link State} instead of a single {@link com.swirlds.state.spi.WritableStates}
 * because a listening client will want to be notified of changes to all {@link com.swirlds.state.spi.WritableStates}
 * returned by the {@link State}.
 * <p>
 * All callbacks have default no-op implementations.
 */
public interface StateChangeListener {
    /**
     * The types of state that can change.
     */
    enum StateType {
        MAP,
        QUEUE,
        SINGLETON,
    }

    /**
     * The target state types that the listener is interested in.
     * @return the target state types
     */
    Set<StateType> stateTypes();

    /**
     * Save the state change when an entry is added in to a map.
     *
     * @param <K> The type of the key
     * @param <V> The type of the value
     * @param stateId The id of the map
     * @param key The key added to the map
     * @param value The value added to the map
     */
    default <K, V> void mapUpdateChange(int stateId, @NonNull K key, @NonNull V value) {}

    /**
     * Save the state change when an entry is removed from a map.
     *
     * @param <K> The type of the key
     * @param stateId The id of the map
     * @param key The key removed from the map
     */
    default <K> void mapDeleteChange(int stateId, @NonNull K key) {}

    /**
     * Save the state change when a value is added to a queue
     *
     * @param <V> The type of the value
     * @param stateId The id of the queue
     * @param value The value added to the queue
     */
    default <V> void queuePushChange(int stateId, @NonNull V value) {}

    /**
     * Save the state change when a value is removed from a queue
     *
     * @param stateId The label of the queue
     */
    default void queuePopChange(int stateId) {}

    /**
     * Save the state change when the value of a singleton is written.
     *
     * @param <V> The type of the value
     * @param stateId The id of the singleton
     * @param value The value of the singleton
     */
    default <V> void singletonUpdateChange(int stateId, @NonNull V value) {}
}
