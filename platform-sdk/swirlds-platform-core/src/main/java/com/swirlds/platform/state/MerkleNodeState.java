// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Represent a state backed up by the Merkle tree. It's a {@link State} implementation that is backed by a Merkle tree.
 * It provides methods to manage the service states in the merkle tree.
 */
public interface MerkleNodeState extends State {

    /**
     * @return an instance representing a root of the Merkle tree. For the most of the implementations
     * this default implementation will be sufficient. But some implementations of the state may be "logical" - they
     * are not `MerkleNode` themselves but are backed by the Merkle tree implementation (e.g. a Virtual Map).
     */
    default MerkleNode getRoot() {
        return (MerkleNode) this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    MerkleNodeState copy();

    /**
     * Initializes the defined service state.
     *
     * @param md The metadata associated with the state.
     */
    default void initializeState(@NonNull final StateMetadata<?, ?> md) {
        throw new UnsupportedOperationException();
    }

    /**
     * Unregister a service without removing its nodes from the state.
     * <p>
     * Services such as the PlatformStateService and RosterService may be registered
     * on a newly loaded (or received via Reconnect) SignedState object in order
     * to access the PlatformState and RosterState/RosterMap objects so that the code
     * can fetch the current active Roster for the state and validate it. Once validated,
     * the state may need to be loaded into the system as the actual state,
     * and as a part of this process, the States API
     * is going to be initialized to allow access to all the services known to the app.
     * However, the States API initialization is guarded by a
     * {@code state.getReadableStates(PlatformStateService.NAME).isEmpty()} check.
     * So if this service has previously been initialized, then the States API
     * won't be initialized in full.
     * <p>
     * To prevent this and to allow the system to initialize all the services,
     * we unregister the PlatformStateService and RosterService after the validation is performed.
     * <p>
     * Note that unlike the {@link #removeServiceState(String, int)} method in this class,
     * the unregisterService() method will NOT remove the merkle nodes that store the states of
     * the services being unregistered. This is by design because these nodes will be used
     * by the actual service states once the app initializes the States API in full.
     *
     * @param serviceName a service to unregister
     */
    void unregisterService(@NonNull final String serviceName);

    /**
     * Removes the node and metadata from the state merkle tree.
     *
     * @param serviceName The service name. Cannot be null.
     * @param stateId The state ID
     */
    void removeServiceState(@NonNull final String serviceName, final int stateId);

    /**
     * Loads a snapshot of a state.
     * @param targetPath The path to load the snapshot from.
     */
    default MerkleNodeState loadSnapshot(final @NonNull Path targetPath) throws IOException {
        throw new UnsupportedOperationException();
    }
}
