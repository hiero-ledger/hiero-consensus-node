// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * An implementaion of this class is responsible
 * @param <T> a type of the snapshot source, should implement {@link MerkleNodeStateAware}
 */
public interface StateLifecycleManager<T extends MerkleNodeStateAware> {

    /**
     * Set the initial State. This method should only be on a startup of after a reconnect.
     *
     * @param state the initial state
     */
    void initState(@NonNull final MerkleNodeState state, boolean onStartup);

    /**
     * Get the mutable state.
     */
    MerkleNodeState getMutableState();

    /**
     * Get the latest immutable state.
     * @return the latest immutable state.
     */
    MerkleNodeState getLatestImmutableState();

    /**
     * Sets the state to create a snapshot from.
     * @param source the state to create a snapshot from.
     */
    void setSnapshotSource(@NonNull T source);

    T getSnapshotSource();

    /**
     * Creates a snapshot for the state that was previously set with {@link #setSnapshotSource(MerkleNodeStateAware)}.
     * The state has to be hashed before calling this method. Once the snapshot is created, the manager releases the source
     * state of the snapshot and clears the reference to it.
     *
     * @param targetPath The path to save the snapshot.
     */
    void createSnapshot(@NonNull Path targetPath);

    /**
     * Loads a snapshot of a state and uses it as a new mutable state.
     *
     * @param targetPath The path to load the snapshot from.
     * @return mutable copy of the loaded state
     */
    MerkleNodeState loadSnapshot(@NonNull Path targetPath);

    /**
     * Creates a mutable copy of the state. The previous mutable state becomes immutable,
     * replacing the latest immutable state.
     *
     * @return a mutable copy of the current mutable state which became the latest immutable state.
     */
    MerkleNodeState copyMutableState();
}
