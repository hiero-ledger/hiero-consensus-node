// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import com.swirlds.base.state.Mutable;
import com.swirlds.common.FastCopyable;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.Releasable;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Hashable;

/**
 * The full state used of the app. The primary implementation is based on a merkle tree, and the data
 * structures provided by the hashgraph platform. But most of our code doesn't need to know that
 * detail, and are happy with just the API provided by this interface.
 */
public interface State extends Mutable, Releasable, Hashable {
    /**
     * Returns a {@link ReadableStates} for the given named service. If such a service doesn't
     * exist, an empty {@link ReadableStates} is returned.
     *
     * @param serviceName The name of the service.
     * @return A collection of {@link ReadableKVState} instances belonging to the service.
     */
    @NonNull
    ReadableStates getReadableStates(@NonNull String serviceName);

    /**
     * Returns a {@link WritableStates} for the given named service. If such a service doesn't
     * exist, an empty {@link WritableStates} is returned.
     *
     * @param serviceName The name of the service.
     * @return A collection of {@link WritableKVState} instance belonging to the service.
     */
    @NonNull
    WritableStates getWritableStates(@NonNull String serviceName);

    /**
     * Registers a listener to be notified on each commit if the {@link WritableStates} created by this {@link State}
     * are marked as {@link CommittableWritableStates}.
     *
     * @param listener The listener to be notified.
     * @throws UnsupportedOperationException if the state does not support listeners.
     */
    default void registerCommitListener(@NonNull final StateChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Unregisters a listener from being notified on each commit if the {@link WritableStates} created by this {@link State}
     * are marked as {@link CommittableWritableStates}.
     * @param listener The listener to be unregistered.
     * @throws UnsupportedOperationException if the state does not support listeners.
     */
    default void unregisterCommitListener(@NonNull final StateChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a calculated hash of the state.
     */
    @NonNull
    default Hash getHash() {
        throw new UnsupportedOperationException();
    }

    /**
     * Answers the question if the state is already hashed.
     *
     * @return true if the state is already hashed, false otherwise.
     */
    default boolean isHashed() {
        throw new UnsupportedOperationException();
    }

    /**
     * Hashes the state on demand if it is not already hashed. If the state is already hashed, this method is a no-op.
     */
    default void computeHash() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a JSON string containing information about the current state.
     * @return A JSON representation of the state information, or an empty string if no information is available.
     */
    default String getInfoJson() {
        return "";
    }

    /**
     * Commit all singleton states for every registered service.
     */
    default void commitSingletons() {}

    /**
     * Initializes the defined service state.
     *
     * @param md The metadata associated with the state.
     */
    default void initializeState(@NonNull StateMetadata<?, ?> md) {}

    /**
     * Removes the node and metadata from the state merkle tree.
     *
     * @param serviceName The service name. Cannot be null.
     * @param stateId The state ID
     */
    default void removeServiceState(@NonNull String serviceName, int stateId) {}

    /**
     * Determines if an object/copy is immutable or not.
     * Only the most recent copy must be mutable.
     *
     * @return Whether the object is immutable or not
     */
    @Override
    default boolean isImmutable() {
        return true;
    }
}
