// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.crypto.Hash;

/**
 * Implementations of this interface are responsible for managing the state lifecycle:
 * <ul>
 * <li>Maintaining references to a mutable state and the latest immutable state.</li>
 * <li>Creating snapshots of the state.</li>
 * <li>Loading snapshots of the state.</li>
 * <li>Creating a mutable copy of the state, while making the current mutable state immutable.</li>
 * </ul>
 * <p>
 * An implementation creates an initial genesis state eagerly upon construction. This genesis state is
 * immediately available via {@link #getMutableState()}. If the node is restarting from a saved state,
 * calling {@link #loadSnapshot(Path)} will replace the genesis state with the loaded state. If the node
 * is reconnecting, calling {@link #initWithState(Object)} will replace the current state with
 * the reconnect state.
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
     * Loads a snapshot of a state from disk, initializes the manager with the loaded state (replacing the
     * eagerly-created genesis state), and returns the hash of the original immutable snapshot as it was on disk.
     * After this call, the loaded state is available via {@link #getMutableState()}.
     *
     * @param targetPath The path to load the snapshot from.
     * @return the hash of the original (pre-copy) immutable state as stored on disk
     * @throws IOException if the snapshot cannot be read
     */
    @NonNull
    Hash loadSnapshot(@NonNull Path targetPath) throws IOException;

    /**
     * Initialize the manager with the provided state. This method creates a copy of the provided state and uses the copy
     * as a mutable state. The passed state becomes the latest immutable state registered in the manager.
     *
     * @param state the state to initialize with
     */
    void initWithState(@NonNull S state);

    /**
     * Creates a mutable copy of the mutable state. The previous mutable state becomes immutable,
     * replacing the latest immutable state.
     *
     * @return a mutable copy of the previous mutable state
     */
    S copyMutableState();
}
