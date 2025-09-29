// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.InlinePcesWriter;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.publisher.PlatformPublisher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.snapshot.StateDumpRequest;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.roster.RosterHistory;

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
     * Flushes the intake pipeline. After this method is called, all components in the intake pipeline (i.e. components
     * prior to the consensus engine) will have been flushed. Additionally, things will be flushed an order that
     * guarantees that there will be no remaining work in the intake pipeline (as long as there are no additional events
     * added to the intake pipeline, and as long as there are no events released by the orphan buffer).
     */
    public void flushIntakePipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        components.eventHasherWiring().flush();
        components.internalEventValidatorWiring().flush();
        components.eventDeduplicatorWiring().flush();
        components.eventSignatureValidatorWiring().flush();
        components.orphanBufferWiring().flush();
        components.pcesInlineWriterWiring().flush();
        components.gossipWiring().flush();
        components.consensusEngineWiring().flush();
        components.applicationTransactionPrehandlerWiring().flush();
        components.eventCreationManagerWiring().flush();
        components.branchDetectorWiring().flush();
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
        components.statusStateMachineWiring().flush();

        // Phase 1: squelch
        // Break cycles in the system. Flush squelched components just in case there is a task being executed when
        // squelch is activated.
        components.consensusEngineWiring().startSquelching();
        components.consensusEngineWiring().flush();
        components.eventCreationManagerWiring().startSquelching();
        components.eventCreationManagerWiring().flush();

        // Also squelch the transaction handler. It isn't strictly necessary to do this to prevent dataflow through
        // the system, but it prevents the transaction handler from wasting time handling rounds that don't need to
        // be handled.
        components.transactionHandlerWiring().startSquelching();
        components.transactionHandlerWiring().flush();

        // Phase 2: flush
        // All cycles have been broken via squelching, so now it's time to flush everything out of the system.
        flushIntakePipeline();
        components.stateHasherWiring().flush();
        components.stateSignatureCollectorWiring().flush();
        components.transactionHandlerWiring().flush();
        components.branchDetectorWiring().flush();
        components.branchReporterWiring().flush();

        // Phase 3: stop squelching
        // Once everything has been flushed out of the system, it's safe to stop squelching.
        components.consensusEngineWiring().stopSquelching();
        components.eventCreationManagerWiring().stopSquelching();
        components.transactionHandlerWiring().stopSquelching();

        // Phase 4: clear
        // Data is no longer moving through the system. Clear all the internal data structures in the wiring objects.
        components
                .eventDeduplicatorWiring()
                .getInputWire(EventDeduplicator::clear)
                .inject(NoInput.getInstance());
        components.orphanBufferWiring().getInputWire(OrphanBuffer::clear).inject(NoInput.getInstance());
        components.gossipWiring().getClearInput().inject(NoInput.getInstance());
        components
                .stateSignatureCollectorWiring()
                .getInputWire(StateSignatureCollector::clear)
                .inject(NoInput.getInstance());
        components
                .eventCreationManagerWiring()
                .getInputWire(EventCreatorModule::clear)
                .inject(NoInput.getInstance());
        components.branchDetectorWiring().getInputWire(BranchDetector::clear).inject(NoInput.getInstance());
        components.branchReporterWiring().getInputWire(BranchReporter::clear).inject(NoInput.getInstance());
    }

    /**
     * Start gossiping.
     */
    public void startGossip() {
        components.gossipWiring().getStartInput().inject(NoInput.getInstance());
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
     * Get the status action submitter.
     *
     * @return the status action submitter
     */
    @NonNull
    public StatusActionSubmitter getStatusActionSubmitter() {
        return action -> components
                .statusStateMachineWiring()
                .getInputWire(StatusStateMachine::submitStatusAction)
                .put(action);
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
        components.gossipWiring().flush();
    }

    /**
     * Inject a new consensus snapshot into all components that need it. This will happen at restart and reconnect
     * boundaries.
     *
     * @param consensusSnapshot the new consensus snapshot
     */
    public void consensusSnapshotOverride(@NonNull final ConsensusSnapshot consensusSnapshot) {
        components
                .consensusEngineWiring()
                .getInputWire(ConsensusEngine::outOfBandSnapshotUpdate)
                .inject(consensusSnapshot);

        if (components.platformPublisherWiring().getSchedulerType() != TaskSchedulerType.NO_OP) {
            components
                    .platformPublisherWiring()
                    .getInputWire(PlatformPublisher::publishSnapshotOverride)
                    .inject(consensusSnapshot);
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
                .statusStateMachineWiring()
                .getInputWire(StatusStateMachine::submitStatusAction)
                .put(action);
    }

    /**
     * @see StateSignatureCollector#addReservedState
     */
    public void putSignatureCollectorState(@NonNull final ReservedSignedState reserve) {
        components
                .stateSignatureCollectorWiring()
                .getInputWire(StateSignatureCollector::addReservedState)
                .put(reserve);
    }

    /**
     * @see EventSignatureValidator#updateRosterHistory
     */
    public void injectRosterHistory(@NonNull final RosterHistory rosterHistory) {
        components
                .eventSignatureValidatorWiring()
                .getInputWire(EventSignatureValidator::updateRosterHistory)
                .inject(rosterHistory);
    }

    /**
     * @see InlinePcesWriter#registerDiscontinuity
     */
    public void registerPcesDiscontinuity(final long round) {
        components
                .pcesInlineWriterWiring()
                .getInputWire(InlinePcesWriter::registerDiscontinuity)
                .inject(round);
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
     * @see InlinePcesWriter#setMinimumAncientIdentifierToStore
     */
    public void injectPcesMinimumGenerationToStore(@NonNull final long minimumGenerationNonAncientForOldestState) {
        components
                .pcesInlineWriterWiring()
                .getInputWire(InlinePcesWriter::setMinimumAncientIdentifierToStore)
                .inject(minimumGenerationNonAncientForOldestState);
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
}
