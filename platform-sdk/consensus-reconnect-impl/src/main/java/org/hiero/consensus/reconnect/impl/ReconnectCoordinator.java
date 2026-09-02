// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusSnapshotOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.status.StatusMonitorModule;
import org.hiero.consensus.status.actions.PlatformStatusAction;

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
     * @see StatusMonitorModule#platformStatusActionInputWire()
     */
    public void submitStatusAction(@NonNull final PlatformStatusAction action) {
        buildingBlocks.statusMonitorModule().platformStatusActionInputWire().put(action);
    }

    /**
     * Resume gossiping.
     */
    public void resumeGossip() {
        buildingBlocks.gossipModule().resumeInputWire().inject(NoInput.getInstance());
    }

    /**
     * Pause gossiping.
     */
    public void pauseGossip() {
        buildingBlocks.gossipModule().pauseInputWire().inject(NoInput.getInstance());
    }

    /**
     * @see AppNotifier#sendReconnectCompleteNotification
     */
    public void sendReconnectCompleteNotification(@NonNull final SignedState signedState) {
        buildingBlocks
                .notifierWiring()
                .getInputWire(AppNotifier::sendReconnectCompleteNotification)
                .put(new ReconnectCompleteNotification(
                        signedState.getRound(), signedState.getConsensusTimestamp(), signedState.getState()));
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
        this.registerPcesDiscontinuity(signedState.getRound());
    }

    /**
     * @see EventIntakeModule#rosterHistoryInputWire()
     */
    private void injectRosterHistory(@NonNull final RosterHistory rosterHistory) {
        buildingBlocks.eventIntakeModule().rosterHistoryInputWire().inject(rosterHistory);
    }

    /**
     * @see PcesModule#discontinuityInputWire()
     */
    private void registerPcesDiscontinuity(final long round) {
        buildingBlocks.pcesModule().discontinuityInputWire().inject(round);
    }
}
