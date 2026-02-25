// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import com.hedera.pbj.runtime.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Essentially, a map of {@link ReadableKVState}s. Each state may be retrieved by key.
 */
public interface ReadableStates {

    /**
     * Gets the {@link ReadableKVState} associated with the given stateKey. If the state cannot be
     * found, an exception is thrown. This should **never** happen in an application, and represents
     * a fatal bug. Applications must only ask for states that they have previously registered with
     * the {@link Schema}.
     *
     * <p>This method is idempotent. When called with the same stateKey, the same {@link
     * ReadableKVState} instance is returned.
     *
     * @param stateId The ID used for looking up state
     * @return The state for that ID. This will never be null.
     * @param <K> The key type in the state.
     * @param <V> The value type in the state.
     * @throws NullPointerException if stateKey is null.
     * @throws IllegalArgumentException if the state cannot be found.
     */
    @NonNull
    <K, V> ReadableKVState<K, V> get(final int stateId);

    @NonNull
    <T> ReadableSingletonState<T> getSingleton(final int stateId);

    @NonNull
    <E> ReadableQueueState<E> getQueue(final int stateId);

    /**
     * Gets whether the given state ID is a member of this set.
     *
     * @param stateId The state ID
     * @return true if a subsequent call to {@link #get(int)} with this state key would succeed.
     */
    boolean contains(final int stateId);

    /**
     * Gets the set of all state keys supported by this map of states.
     *
     * @return The set of all state keys.
     */
    @NonNull
    Set<Integer> stateIds();

    /**
     * Gets the number of states contained in this instance.
     *
     * @return The number of states. The value will be non-negative.
     */
    default int size() {
        return stateIds().size();
    }

    /**
     * Gets whether this instance is empty, that is, it has no states.
     *
     * @return True if there are no states in this instance.
     */
    default boolean isEmpty() {
        return stateIds().isEmpty();
    }
}
