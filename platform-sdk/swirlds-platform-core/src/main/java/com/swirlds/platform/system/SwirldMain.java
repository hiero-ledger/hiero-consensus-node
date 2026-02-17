// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * To implement a swirld, create a class that implements SwirldMain. Its constructor should have no parameters, and its
 * run() method should run until the user quits the swirld.
 */
public interface SwirldMain extends Runnable, ExecutionLayer {

    /**
     * <p>
     * This should only be called by the Platform. It is passed a reference to the platform, so the SwirldMain will know
     * who to call. (This is dependency injection).
     * </p>
     *
     * <p>
     * Any changes necessary to initialize {@link State} should be made in
     * {@link ConsensusStateEventHandler#onStateInitialized(State, Platform, InitTrigger, SemanticVersion)}
     * </p>
     *
     * @param platform the Platform that instantiated this SwirldMain
     * @param selfId   the ID number for this member (myself)
     */
    void init(@NonNull final Platform platform, @NonNull final NodeId selfId);

    /**
     * This is where the app manages the screen and I/O, and creates transactions as needed. It should return when the
     * user quits the app, but may also return earlier.
     */
    @Override
    void run();

    /**
     * State lifecycle manager associated with this app
     *
     * @return state lifecycle manager
     */
    @NonNull
    StateLifecycleManager<VirtualMapState, VirtualMap> getStateLifecycleManager();

    /**
     * Instantiate and return a state root object for this SwirldMain object.
     * The returned state root object could be one of the following:
     * <ul>
     *     <li>(Deprecated) Actual root node of the merkle state tree
     *         - an instance of {@code HederaStateRoot}.
     *     </li>
     *     <li>A wrapper around the root node
     *         - an instance of {@code VirtualMapStateImpl}.
     *     </li>
     * </ul>
     *
     * @return state root object
     */
    @NonNull
    State newStateRoot();

    /**
     * Instantiate and return a new instance of the consensus state event handler for this SwirldMain object.
     * @return consensus state event handler
     */
    ConsensusStateEventHandler newConsensusStateEvenHandler();

    /**
     * <p>
     * Get the current software version.
     * </p>
     *
     * <ul>
     * <li>
     * This version should not change except when a node is restarted.
     * </li>
     * <li>
     * Every time a node restarts, the supplied version must be greater or equal to the previous version.
     * </li>
     * <li>
     * Every supplied version for a particular app should have the same type. Failure to follow this
     * restriction may lead to miscellaneous {@link ClassCastException}s.
     * </li>
     * </ul>
     *
     * @return the current version
     */
    @NonNull
    SemanticVersion getSemanticVersion();
}
