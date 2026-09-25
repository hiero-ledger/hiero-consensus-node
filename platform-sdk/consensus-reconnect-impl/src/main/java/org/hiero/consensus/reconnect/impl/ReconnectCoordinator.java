// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;

import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Responsible for coordinating activities through the component's wire for reconnect-related operations.
 */
public class ReconnectCoordinator {

    private final ConsensusLayerAdapterBuildingBlocks buildingBlocks;

    /**
     * Constructor
     *
     * @param buildingBlocks the building blocks of the consensus layer
     */
    public ReconnectCoordinator(@NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        this.buildingBlocks = Objects.requireNonNull(buildingBlocks);
    }

    /**
     * Load the received signed state into the platform (inline former ReconnectStateLoader#loadReconnectState).
     *
     * @param signedState the signed state to load into the platform
     */
    public void loadReconnectState(@NonNull final SignedState signedState) {
        buildingBlocks
                .issDetectionModule()
                .overridingStateInputWire()
                .put(signedState.reserve("reconnect state to issDetector"));

        buildingBlocks
                .transactionHandlingModule()
                .latestImmutableStateInputWire()
                .put(signedState.reserve("set latest immutable to reconnect state"));
        // this will log the state and send it to the signature collector which will send it to be written to disk.
        // in the future, we might not send it to the collector because it already has all the signatures
        // if this is the case, we must make sure to send it to the writer directly
        buildingBlocks.stateModule().sendState(signedState);

        final State state = signedState.getState();

        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHashOf(state), true);
        buildingBlocks.runningEventHashOverrideWiring().runningHashUpdateInput().inject(runningEventHashOverride);
    }
}
