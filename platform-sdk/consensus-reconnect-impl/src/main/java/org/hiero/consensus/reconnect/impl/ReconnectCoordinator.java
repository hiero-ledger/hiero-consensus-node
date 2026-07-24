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
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
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
import org.hiero.consensus.status.StatusStateMachine;
import org.hiero.consensus.status.actions.PlatformStatusAction;

/**
 * Responsible for coordinating activities through the component's wire for reconnect-related operations.
 */
public class ReconnectCoordinator {

    private final ConsensusLayerBuildingBlocks buildingBlocks;

    /**
     * Constructor
     *
     * @param buildingBlocks the building blocks of the consensus layer
     */
    public ReconnectCoordinator(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        this.buildingBlocks = Objects.requireNonNull(buildingBlocks);
    }

    /**
     * @see StatusStateMachine#submitStatusAction
     */
    public void submitStatusAction(@NonNull final PlatformStatusAction action) {
        buildingBlocks.statusMonitorModule().submitStatusAction(action);
    }

    /**
     * Safely clears the system in preparation for reconnect. After this method is called, there should be no work
     * sitting in any of the wiring queues, and all internal data structures within wiring components that need to be
     * cleared to prepare for a reconnect should be cleared.
     */
    public void clear() {
        // Important: the order of the lines within this function are important. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        // Phase 0: flush the status state machine.
        // When reconnecting, this will force us to adopt a status that will halt event creation and gossip.
        buildingBlocks.statusMonitorModule().flush();

        // Phase 1: squelch
        // Break cycles in the system. Flush squelched components just in case there is a task being executed when
        // squelch is activated.
        buildingBlocks.hashgraphModule().startSquelching();
        buildingBlocks.hashgraphModule().flush();
        buildingBlocks.eventCreatorModule().startSquelching();
        buildingBlocks.eventCreatorModule().flush();

        // Also squelch the transaction handler. It isn't strictly necessary to do this to prevent dataflow through
        // the system, but it prevents the transaction handler from wasting time handling rounds that don't need to
        // be handled.
        buildingBlocks.transactionHandlingModule().startSquelching();
        buildingBlocks.transactionHandlingModule().flush();

        // Phase 2: flush
        // All cycles have been broken via squelching, so now it's time to flush everything out of the system.
        buildingBlocks.pipelineFlusher().flushPrimaryPipeline();

        // Phase 3: stop squelching
        // Once everything has been flushed out of the system, it's safe to stop squelching.
        buildingBlocks.hashgraphModule().stopSquelching();
        buildingBlocks.eventCreatorModule().stopSquelching();
        buildingBlocks.transactionHandlingModule().stopSquelching();

        // Phase 4: clear
        // Data is no longer moving through the system. Clear all the internal data structures in the wiring objects.
        buildingBlocks.eventIntakeModule().clearComponentsInputWire().inject(NoInput.getInstance());
        buildingBlocks.gossipModule().clearInputWire().inject(NoInput.getInstance());
        buildingBlocks.stateModule().clearInputWire().inject(NoInput.getInstance());
        buildingBlocks.eventCreatorModule().clearCreationMangerInputWire().inject(NoInput.getInstance());
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
     * @param configuration the configuration to read necessary config values from
     * @param signedState the signed state to load into the platform
     */
    public void loadReconnectState(@NonNull final Configuration configuration, @NonNull final SignedState signedState) {
        buildingBlocks
                .issDetectionModule()
                .overrideIssDetectorState(signedState.reserve("reconnect state to issDetector"));

        buildingBlocks
                .transactionHandlingModule()
                .latestImmutableStateInputWire()
                .put(signedState.reserve("set latest immutable to reconnect state"));
        // this will log the state and send it to the signature collector which will send it to be written to disk.
        // in the future, we might not send it to the collector because it already has all the signatures
        // if this is the case, we must make sure to send it to the writer directly
        buildingBlocks.stateModule().sendState(signedState);

        final State state = signedState.getState();

        final ConsensusSnapshot consensusSnapshot = requireNonNull(consensusSnapshotOf(state));
        buildingBlocks.hashgraphModule().consensusSnapshotOverride(consensusSnapshot);

        final ReadableRosterStore rosterStore =
                new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.SERVICE_NAME));
        final RosterHistory rosterHistory = rosterStore.getRosterHistory();
        this.injectRosterHistory(rosterHistory);

        final int roundsNonAncient =
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
        buildingBlocks
                .platformCoordinator()
                .updateEventWindow(EventWindowUtils.createEventWindow(consensusSnapshot, roundsNonAncient));

        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHashOf(state), true);
        buildingBlocks.runningEventHashOverrideWiring().updateRunningHash(runningEventHashOverride);
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
