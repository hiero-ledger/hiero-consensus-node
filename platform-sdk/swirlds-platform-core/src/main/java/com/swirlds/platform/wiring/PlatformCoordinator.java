// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.system.PlatformMonitor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.status.StatusActionSubmitter;
import org.hiero.consensus.status.StatusStateMachine;
import org.hiero.consensus.status.actions.PlatformStatusAction;

/**
 * Responsible for coordinating activities through the component's wire for the platform.
 *
 * @param components
 */
public record PlatformCoordinator(@NonNull PlatformComponents components) implements StatusActionSubmitter {

    /**
     * Constructor
     */
    public PlatformCoordinator {
        Objects.requireNonNull(components);
    }

    /**
     * Flushes the primary consensus-layer pipeline component-by-component, in upstream-to-downstream order: intake →
     * pces → gossip → hashgraph → transaction handling (pre-handler then handler) → event creation → state hasher.
     *
     * <p>This only flushes the pipeline; it does not decide whether in-flight work is delivered or discarded. That
     * depends on the state of the components when it is called — live components deliver (the PCES-replay caller),
     * squelched components discard (the reconnect {@code clear()} caller).
     *
     * <p>A single ordered pass leaves no work behind only while (1) no new events enter the intake module during the
     * flush and (2) the orphan buffer releases nothing in response to an event-window update. Both callers satisfy
     * these; see {@code rules/RUL-002} in the consensus-layer knowledge base for why one pass suffices and what would
     * break it.
     *
     * <p>Do not change the order of the calls below without consulting the wiring diagram.
     */
    public void flushPrimaryPipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        components.eventIntakeModule().flush();
        components.pcesModule().flush();
        components.gossipModule().flush();
        components.hashgraphModule().flush();
        components.transactionHandlingModule().flush();
        components.eventCreatorModule().flush();
        components.stateHasherWiring().flush();
    }

    /**
     * Start gossiping.
     */
    public void startGossip() {
        components.gossipModule().startInputWire().inject(NoInput.getInstance());
    }

    /**
     * Forward a state to the hash logger.
     *
     * @param signedState the state to forward
     */
    public void sendStateToHashLogger(@NonNull final SignedState signedState) {
        if (signedState.getState().getHash() != null) {
            final ReservedSignedState stateReservedForHasher = signedState.reserve("logging state hash");

            final boolean offerResult = components
                    .hashLoggerWiring()
                    .getInputWire(HashLogger::logHashes)
                    .offer(stateReservedForHasher);
            if (!offerResult) {
                stateReservedForHasher.close();
            }
        }
    }

    /**
     * Update the running hash for all components that need it.
     *
     * @param runningHashUpdate the object containing necessary information to update the running hash
     */
    public void updateRunningHash(@NonNull final RunningEventHashOverride runningHashUpdate) {
        components.runningEventHashOverrideWiring().runningHashUpdateInput().inject(runningHashUpdate);
    }

    /**
     * Pass an overriding state to the ISS detector.
     *
     * @param state the overriding state
     */
    public void overrideIssDetectorState(@NonNull final ReservedSignedState state) {
        components.issDetectionModule().overridingStateInputWire().put(state);
    }

    /**
     * Signal the end of the preconsensus replay to the ISS detector.
     */
    public void signalEndOfPcesReplay() {
        components.issDetectionModule().signalEndOfPreconsensusReplayInputWire().put(NoInput.getInstance());
    }

    /**
     * Inject a new event window into all components that need it.
     *
     * @param eventWindow the new event window
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Future work: this method can merge with consensusSnapshotOverride
        components
                .eventWindowManagerWiring()
                .getInputWire(EventWindowManager::updateEventWindow)
                .inject(eventWindow);

        // Since there is asynchronous access to the shadowgraph, it's important to ensure that
        // it has fully ingested the new event window before continuing.
        components.gossipModule().flush();
    }

    /**
     * Inject a new consensus snapshot into all components that need it. This will happen at restart and reconnect
     * boundaries.
     *
     * @param consensusSnapshot the new consensus snapshot
     */
    public void consensusSnapshotOverride(@NonNull final ConsensusSnapshot consensusSnapshot) {
        components.hashgraphModule().consensusSnapshotInputWire().inject(consensusSnapshot);
    }

    /**
     * Start the wiring framework.
     */
    public void start() {
        components.model().start();
    }

    /**
     * Stop the wiring framework.
     */
    public void stop() {
        components.model().stop();
    }

    /**
     * @see StatusStateMachine#submitStatusAction
     */
    public void submitStatusAction(@NonNull final PlatformStatusAction action) {
        components
                .platformMonitorWiring()
                .getInputWire(PlatformMonitor::submitStatusAction)
                .put(action);
    }

    /**
     * Flush the platform status state machine
     */
    public void flushPlatformStatus() {
        components.platformMonitorWiring().flush();
    }

    /**
     * @see PcesModule#minimumBirthRoundInputWire()
     */
    public void injectPcesMinimumBirthRoundToStore(@NonNull final long minimumBirthRoundNonAncientForOldestState) {
        components.pcesModule().minimumBirthRoundInputWire().inject(minimumBirthRoundNonAncientForOldestState);
    }

    /**
     * @see StateSignatureCollector#addReservedState(ReservedSignedState)
     */
    public void injectSignatureCollectorState(@NonNull final ReservedSignedState reservedSignedState) {
        components
                .stateSignatureCollectorWiring()
                .getInputWire(StateSignatureCollector::addReservedState)
                .put(reservedSignedState);
    }

    /**
     * @see EventCreatorModule#quiescenceCommandInputWire()
     */
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        components
                .platformMonitorWiring()
                .getInputWire(PlatformMonitor::quiescenceCommand)
                .inject(quiescenceCommand);
        components.eventCreatorModule().quiescenceCommandInputWire().inject(quiescenceCommand);
    }
}
