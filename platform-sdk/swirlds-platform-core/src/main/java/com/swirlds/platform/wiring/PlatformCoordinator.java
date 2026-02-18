// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.snapshot.StateDumpRequest;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;

/**
 * Responsible for coordinating activities through the component's wire for the platform.
 *
 * @param components
 */
public record PlatformCoordinator(
        @NonNull PlatformComponents components, @NonNull ApplicationCallbacks callbacks)
        implements StatusActionSubmitter {

    /**
     * Constructor
     */
    public PlatformCoordinator {
        Objects.requireNonNull(components);
        Objects.requireNonNull(callbacks);
    }

    /**
     * Flushes the intake pipeline. After this method is called, all components in the intake pipeline (i.e. components
     * prior to the consensus engine) will have been flushed. Additionally, things will be flushed an order that
     * guarantees that there will be no remaining work in the intake pipeline (as long as there are no additional events
     * added to the intake pipeline, and as long as there are no events released by the orphan buffer).
     */
    public void flushIntakePipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        components.eventIntakeModule().flush();
        components.pcesModule().flush();
        components.gossipModule().flush();
        components.hashgraphModule().flush();
        components.applicationTransactionPrehandlerWiring().flush();
        components.eventCreatorModule().flush();
        components.branchDetectorWiring().flush();
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
        components
                .issDetectorWiring()
                .getInputWire(IssDetector::overridingState)
                .put(state);
    }

    /**
     * Signal the end of the preconsensus replay to the ISS detector.
     */
    public void signalEndOfPcesReplay() {
        components
                .issDetectorWiring()
                .getInputWire(IssDetector::signalEndOfPreconsensusReplay)
                .put(NoInput.getInstance());
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
        if (callbacks.snapshotOverrideConsumer() != null) {
            callbacks.snapshotOverrideConsumer().accept(consensusSnapshot);
        }
    }

    /**
     * Flush the transaction handler.
     */
    public void flushTransactionHandler() {
        components.transactionHandlerWiring().flush();
    }

    /**
     * Flush the state hasher.
     */
    public void flushStateHasher() {
        components.stateHasherWiring().flush();
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
     * @see PcesModule#minimumAncientIdentifierInputWire()
     */
    public void injectPcesMinimumGenerationToStore(@NonNull final long minimumGenerationNonAncientForOldestState) {
        components.pcesModule().minimumAncientIdentifierInputWire().inject(minimumGenerationNonAncientForOldestState);
    }

    /**
     * @see com.swirlds.platform.event.preconsensus.PcesReplayer#replayPces
     */
    public void injectPcesReplayerIterator(@NonNull final IOIterator<PlatformEvent> iterator) {
        components.pcesReplayerWiring().pcesIteratorInputWire().inject(iterator);
    }

    /**
     * @see StateSnapshotManager#dumpStateTask
     */
    public void dumpStateToDisk(@NonNull final StateDumpRequest request) {
        components
                .stateSnapshotManagerWiring()
                .getInputWire(StateSnapshotManager::dumpStateTask)
                .put(request);
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
