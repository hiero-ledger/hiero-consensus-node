// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Implementations of this interface are responsible for managing the state lifecycle:
 * <ul>
 * <li>Maintaining references to a mutable state and the latest immutable state.</li>
 * <li>Creating snapshots of the state.</li>
 * <li>Loading snapshots of the state.</li>
 * <li>Creating a mutable copy of the state, while making the current mutable state immutable.</li>
 * </ul>
 *
 * @param <S> the type of the state
 * @param <D> the type of the root node of a Merkle tree
 */
public interface StateLifecycleManager<S, D> {

    /**
     * Create a state from a root node. This method doesn't update the current mutable or immutable state.
     * @param rootNode the root node of a Merkle tree to create a state from
     * @return a state created from the root node
     */
    S createStateFrom(@NonNull D rootNode);

    /**
     * Set the initial State. This method should only be on a startup
     *
     * @param state the initial state
     * @throws IllegalStateException if the state has already been initialized
     */
    void initState(@NonNull S state);

    /**
     * Initialize with the state on reconnect. This method should only be called on a reconnect.
     * @param state the state to initialize with
     */
    void initStateOnReconnect(@NonNull S state);

    /**
     * Get the mutable state. Consecutive calls to this method may return different instances,
     * if this method is not called on the one and the only thread that is calling {@link #copyMutableState}.
     * If a parallel thread calls {@link #copyMutableState}, the returned object will become immutable and
     * on the subsequent call of {@link #copyMutableState} it will be destroyed (unless it was explicitly reserved outside of this class)
     * and, therefore, not usable in some contexts.
     *
     * @return the mutable state.
     */
    S getMutableState();

    /**
     * Get the latest immutable state. Consecutive calls to this method may return different instances
     * if this method is not called on the one and only thread that is calling {@link #copyMutableState}.
     * If a parallel thread calls {@link #copyMutableState}, the returned object will become destroyed (unless it was explicitly reserved outside of this class)
     * and, therefore, not usable in some contexts.
     * <br>
     * If a durable long-term reference to the immutable state returned by this method is required, it is the
     * responsibility of the caller to ensure a reference is maintained to prevent its garbage collection. Also,
     * it is the responsibility of the caller to ensure that the object is not used in contexts in which it may become unusable
     * (e.g., hashing of the destroyed state is not possible).
     *
     * @return the latest immutable state.
     */
    S getLatestImmutableState();

    /**
     * Creates a snapshot for the state provided as a parameter. The state has to be hashed before calling this method.
     *
     * @param state The state to save.
     * @param targetPath The path to save the snapshot.
     */
    void createSnapshot(@NonNull S state, @NonNull Path targetPath);

    /**
     * Loads a snapshot of a state.
     *
     * @param targetPath The path to load the snapshot from.
     * @return mutable copy of the loaded state
     */
    S loadSnapshot(@NonNull Path targetPath) throws IOException;

    /**
     * Creates a mutable copy of the mutable state. The previous mutable state becomes immutable,
     * replacing the latest immutable state.
     *
     * @return a mutable copy of the previous mutable state
     */
    S copyMutableState();

    /**
     * Adds an observer that will be notified when a new immutable state is created.
     *
     * @param observer the observer to add
     */
    void addObserver(Consumer<State> observer);
}
