// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusSnapshotOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Responsible for coordinating activities through the component's wire for reconnect-related operations.
 */
public class ReconnectCoordinator {

    private final PlatformComponents components;
    private final PlatformCoordinator platformCoordinator;

    /**
     * Constructor
     *
     * @param components the components to coordinate
     * @param platformCoordinator the platform coordinator to use for certain operations
     */
    public ReconnectCoordinator(
            @NonNull final PlatformComponents components, @NonNull final PlatformCoordinator platformCoordinator) {
        this.components = requireNonNull(components);
        this.platformCoordinator = requireNonNull(platformCoordinator);
    }

    /**
     * @see StatusStateMachine#submitStatusAction
     */
    public void submitStatusAction(@NonNull final PlatformStatusAction action) {
        platformCoordinator.submitStatusAction(action);
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
        components.platformMonitorWiring().flush();

        // Phase 1: squelch
        // Break cycles in the system. Flush squelched components just in case there is a task being executed when
        // squelch is activated.
        components.hashgraphModule().startSquelching();
        components.hashgraphModule().flush();
        components.eventCreatorModule().startSquelching();
        components.eventCreatorModule().flush();

        // Also squelch the transaction handler. It isn't strictly necessary to do this to prevent dataflow through
        // the system, but it prevents the transaction handler from wasting time handling rounds that don't need to
        // be handled.
        components.transactionHandlerWiring().startSquelching();
        components.transactionHandlerWiring().flush();

        // Phase 2: flush
        // All cycles have been broken via squelching, so now it's time to flush everything out of the system.
        platformCoordinator.flushIntakePipeline();
        components.stateHasherWiring().flush();
        components.stateSignatureCollectorWiring().flush();
        components.transactionHandlerWiring().flush();
        components.branchDetectorWiring().flush();
        components.branchReporterWiring().flush();

        // Phase 3: stop squelching
        // Once everything has been flushed out of the system, it's safe to stop squelching.
        components.hashgraphModule().stopSquelching();
        components.eventCreatorModule().stopSquelching();
        components.transactionHandlerWiring().stopSquelching();

        // Phase 4: clear
        // Data is no longer moving through the system. Clear all the internal data structures in the wiring objects.
        components.eventIntakeModule().clearComponentsInputWire().inject(NoInput.getInstance());
        components.gossipModule().clearInputWire().inject(NoInput.getInstance());
        components
                .stateSignatureCollectorWiring()
                .getInputWire(StateSignatureCollector::clear)
                .inject(NoInput.getInstance());
        components.eventCreatorModule().clearCreationMangerInputWire().inject(NoInput.getInstance());
        components.branchDetectorWiring().getInputWire(BranchDetector::clear).inject(NoInput.getInstance());
        components.branchReporterWiring().getInputWire(BranchReporter::clear).inject(NoInput.getInstance());
    }

    /**
     * Resume gossiping.
     */
    public void resumeGossip() {
        components.gossipModule().resumeInputWire().inject(NoInput.getInstance());
    }

    /**
     * Pause gossiping.
     */
    public void pauseGossip() {
        components.gossipModule().pauseInputWire().inject(NoInput.getInstance());
    }

    /**
     * @see AppNotifier#sendReconnectCompleteNotification
     */
    public void sendReconnectCompleteNotification(@NonNull final SignedState signedState) {
        components
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
        platformCoordinator.overrideIssDetectorState(signedState.reserve("reconnect state to issDetector"));

        components
                .latestImmutableStateNexusWiring()
                .getInputWire(SignedStateNexus::setState)
                .put(signedState.reserve("set latest immutable to reconnect state"));
        platformCoordinator.sendStateToHashLogger(signedState);
        // this will send the state to the signature collector which will send it to be written to disk.
        // in the future, we might not send it to the collector because it already has all the signatures
        // if this is the case, we must make sure to send it to the writer directly
        this.putSignatureCollectorState(signedState.reserve("loading reconnect state into sig collector"));

        final State state = signedState.getState();

        final ConsensusSnapshot consensusSnapshot = requireNonNull(consensusSnapshotOf(state));
        platformCoordinator.consensusSnapshotOverride(consensusSnapshot);

        final RosterHistory rosterHistory = RosterStateUtils.createRosterHistory(state);
        this.injectRosterHistory(rosterHistory);

        final int roundsNonAncient =
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient();
        platformCoordinator.updateEventWindow(EventWindowUtils.createEventWindow(consensusSnapshot, roundsNonAncient));

        final RunningEventHashOverride runningEventHashOverride =
                new RunningEventHashOverride(legacyRunningEventHashOf(state), true);
        platformCoordinator.updateRunningHash(runningEventHashOverride);
        this.registerPcesDiscontinuity(signedState.getRound());
    }

    /**
     * @see StateSignatureCollector#addReservedState
     */
    private void putSignatureCollectorState(@NonNull final ReservedSignedState reserve) {
        components
                .stateSignatureCollectorWiring()
                .getInputWire(StateSignatureCollector::addReservedState)
                .put(reserve);
    }

    /**
     * @see EventIntakeModule#rosterHistoryInputWire()
     */
    private void injectRosterHistory(@NonNull final RosterHistory rosterHistory) {
        components.eventIntakeModule().rosterHistoryInputWire().inject(rosterHistory);
    }

    /**
     * @see PcesModule#discontinuityInputWire()
     */
    private void registerPcesDiscontinuity(final long round) {
        components.pcesModule().discontinuityInputWire().inject(round);
    }
}
