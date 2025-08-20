// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;
import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static com.swirlds.component.framework.wires.SolderType.OFFER;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.transformers.WireFilter;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.components.appcomm.LatestCompleteStateNotifier;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.InlinePcesWriter;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionHandlerResult;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.publisher.PlatformPublisher;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateGarbageCollector;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.system.status.StatusStateMachine;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Queue;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Encapsulates wiring for {@link com.swirlds.platform.SwirldsPlatform}.
 */
public class PlatformWiring {

    /**
     * Wire the components together.
     */
    public static void wire(
            PlatformContext platformContext, @NonNull final ExecutionLayer execution, PlatformComponents w) {
        final InputWire<PlatformEvent> hasherInputWire =
                w.eventHasherWiring().getInputWire(EventHasher::hashEvent, "unhashed event");
        w.gossipWiring().getEventOutput().solderTo(hasherInputWire);

        w.gossipWiring()
                .getSyncLagOutput()
                .solderTo(w.eventCreationManagerWiring().getInputWire(EventCreationManager::reportSyncRoundLag));

        w.eventHasherWiring()
                .getOutputWire()
                .solderTo(w.internalEventValidatorWiring().getInputWire(InternalEventValidator::validateEvent));

        w.internalEventValidatorWiring()
                .getOutputWire()
                .solderTo(w.eventDeduplicatorWiring().getInputWire(EventDeduplicator::handleEvent));
        w.eventDeduplicatorWiring()
                .getOutputWire()
                .solderTo(w.eventSignatureValidatorWiring().getInputWire(EventSignatureValidator::validateSignature));
        w.eventSignatureValidatorWiring()
                .getOutputWire()
                .solderTo(w.orphanBufferWiring().getInputWire(OrphanBuffer::handleEvent));
        final OutputWire<PlatformEvent> splitOrphanBufferOutput =
                w.orphanBufferWiring().getSplitOutput();

        splitOrphanBufferOutput.solderTo(w.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::writeEvent));

        // Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
        // reach consensus with events that might be lost due to a crash
        w.pcesInlineWriterWiring()
                .getOutputWire()
                .solderTo(w.consensusEngineWiring().componentWiring().getInputWire(ConsensusEngine::addEvent));

        // Make sure events are persisted before being gossipped. This prevents accidental branching in the case
        // where an event is created, gossipped, and then the node crashes before the event is persisted.
        // After restart, a node will not be aware of this event, so it can create a branch
        w.pcesInlineWriterWiring().getOutputWire().solderTo(w.gossipWiring().getEventInput(), INJECT);

        // Avoid using events as parents before they are persisted
        w.pcesInlineWriterWiring()
                .getOutputWire()
                .solderTo(w.eventCreationManagerWiring().getInputWire(EventCreationManager::registerEvent));

        w.model()
                .getHealthMonitorWire()
                .solderTo(w.eventCreationManagerWiring().getInputWire(EventCreationManager::reportUnhealthyDuration));

        w.model().getHealthMonitorWire().solderTo(w.gossipWiring().getSystemHealthInput());
        w.model().getHealthMonitorWire()
                .solderTo("executionHealthInput", "healthyDuration", execution::reportUnhealthyDuration);

        splitOrphanBufferOutput.solderTo(w.branchDetectorWiring().getInputWire(BranchDetector::checkForBranches));
        w.branchDetectorWiring()
                .getOutputWire()
                .solderTo(w.branchReporterWiring().getInputWire(BranchReporter::reportBranch));

        final double eventCreationHeartbeatFrequency = platformContext
                .getConfiguration()
                .getConfigData(EventCreationConfig.class)
                .creationAttemptRate();
        w.model()
                .buildHeartbeatWire(eventCreationHeartbeatFrequency)
                .solderTo(w.eventCreationManagerWiring().getInputWire(EventCreationManager::maybeCreateEvent), OFFER);
        w.model()
                .buildHeartbeatWire(platformContext
                        .getConfiguration()
                        .getConfigData(PlatformStatusConfig.class)
                        .statusStateMachineHeartbeatPeriod())
                .solderTo(w.statusStateMachineWiring().getInputWire(StatusStateMachine::heartbeat), OFFER);

        w.eventCreationManagerWiring()
                .getOutputWire()
                .solderTo(w.internalEventValidatorWiring().getInputWire(InternalEventValidator::validateEvent), INJECT);

        if (w.platformPublisherWiring().getSchedulerType() != NO_OP) {
            final OutputWire<PlatformEvent> staleEvent = w.consensusEngineWiring()
                    .getOutputWire()
                    .buildTransformer("staleEvents", "consensusEngineOutput", ConsensusEngineOutput::staleEvents)
                    .buildSplitter("staleEventsSplitter", "stale events");
            staleEvent.solderTo(w.platformPublisherWiring().getInputWire(PlatformPublisher::publishStaleEvent));
        }

        // an output wire that filters out only pre-consensus events from the consensus engine
        final OutputWire<PlatformEvent> consEngineAddedEvents = w.consensusEngineWiring()
                .getOutputWire()
                .buildTransformer(
                        "PreConsensusEvents", "consensusEngineOutput", ConsensusEngineOutput::preConsensusEvents)
                .buildSplitter("PreConsensusEventsSplitter", "preConsensusEvents");
        // pre-handle gets pre-consensus events from the consensus engine
        // the consensus engine ensures that all pre-consensus events either reach consensus of become stale
        consEngineAddedEvents.solderTo(w.applicationTransactionPrehandlerWiring()
                .getInputWire(TransactionPrehandler::prehandleApplicationTransactions));

        w.applicationTransactionPrehandlerWiring()
                .getOutputWire()
                .solderTo(w.stateSignatureCollectorWiring()
                        .getInputWire(StateSignatureCollector::handlePreconsensusSignatures));

        // Split output of StateSignatureCollector into single ReservedSignedStates.
        final OutputWire<ReservedSignedState> splitReservedSignedStateWire = w.stateSignatureCollectorWiring()
                .getOutputWire()
                .buildSplitter("reservedStateSplitter", "reserved state lists");
        // Add another reservation to the signed states since we are soldering to two different input wires
        final OutputWire<ReservedSignedState> allReservedSignedStatesWire =
                splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));

        // Future work: this should be a full component in its own right or folded in with the state file manager.
        final WireFilter<ReservedSignedState> saveToDiskFilter =
                new WireFilter<>(w.model(), "saveToDiskFilter", "states", state -> {
                    if (state.get().isStateToSave()) {
                        return true;
                    }
                    state.close();
                    return false;
                });

        allReservedSignedStatesWire.solderTo(saveToDiskFilter.getInputWire());

        saveToDiskFilter
                .getOutputWire()
                .solderTo(w.stateSnapshotManagerWiring().getInputWire(StateSnapshotManager::saveStateTask));

        // Filter to complete states only and add a 3rd reservation since completes states are used in two input wires.
        final OutputWire<ReservedSignedState> completeReservedSignedStatesWire = allReservedSignedStatesWire
                .buildFilter("completeStateFilter", "states", rs -> {
                    if (rs.get().isComplete()) {
                        return true;
                    } else {
                        // close the second reservation on states that are not passed on.
                        rs.close();
                        return false;
                    }
                })
                .buildAdvancedTransformer(new SignedStateReserver("completeStatesReserver"));
        completeReservedSignedStatesWire.solderTo(
                w.latestCompleteStateNexusWiring().getInputWire(LatestCompleteStateNexus::setStateIfNewer));

        solderEventWindow(w);

        w.pcesReplayerWiring().eventOutput().solderTo(hasherInputWire);

        final OutputWire<ConsensusRound> consensusRoundOutputWire = w.consensusEngineWiring()
                .consensusRoundsOutputWire()
                .buildSplitter("ConsensusRoundsSplitter", "consensus rounds");

        w.pcesReplayerWiring()
                .doneStreamingPcesOutputWire()
                .solderTo(w.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::beginStreamingNewEvents));
        // with inline PCES, the round bypasses the round durability buffer and goes directly to the round handler
        consensusRoundOutputWire.solderTo(
                w.transactionHandlerWiring().getInputWire(TransactionHandler::handleConsensusRound));

        consensusRoundOutputWire.solderTo(
                w.eventWindowManagerWiring().getInputWire(EventWindowManager::extractEventWindow));

        consensusRoundOutputWire
                .buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)
                .solderTo(w.consensusEventStreamWiring().getInputWire(ConsensusEventStream::addEvents));

        // The TransactionHandler output is split into two types: system transactions, and state with complexity.
        final OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                transactionHandlerSysTxnsOutputWire = w.transactionHandlerWiring()
                        .getOutputWire()
                        .buildTransformer(
                                "getSystemTransactions",
                                "transaction handler result",
                                TransactionHandlerResult::systemTransactions);
        transactionHandlerSysTxnsOutputWire.solderTo(
                w.stateSignatureCollectorWiring().getInputWire(StateSignatureCollector::handlePostconsensusSignatures));
        transactionHandlerSysTxnsOutputWire.solderTo(
                w.issDetectorWiring().getInputWire(IssDetector::handleStateSignatureTransactions));

        final OutputWire<StateWithHashComplexity> transactionHandlerStateWithComplexityOutput =
                w.transactionHandlerWiring()
                        .getOutputWire()
                        .buildFilter(
                                "notNullStateFilter",
                                "transaction handler result",
                                thr -> thr.stateWithHashComplexity() != null)
                        .buildAdvancedTransformer(
                                new StateWithHashComplexityReserver("postHandler_stateWithHashComplexityReserver"));

        transactionHandlerStateWithComplexityOutput.solderTo(
                w.savedStateControllerWiring().getInputWire(SavedStateController::markSavedState));

        final OutputWire<ReservedSignedState> transactionHandlerStateOnlyOutput =
                transactionHandlerStateWithComplexityOutput.buildAdvancedTransformer(
                        new StateWithHashComplexityToStateReserver(
                                "postHandler_stateWithHashComplexityToStateReserver"));

        transactionHandlerStateOnlyOutput.solderTo(
                w.latestImmutableStateNexusWiring().getInputWire(SignedStateNexus::setState));
        transactionHandlerStateOnlyOutput.solderTo(
                w.stateGarbageCollectorWiring().getInputWire(StateGarbageCollector::registerState));

        w.savedStateControllerWiring()
                .getOutputWire()
                .solderTo(w.stateHasherWiring().getInputWire(StateHasher::hashState));

        var config = platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);
        w.model()
                .buildHeartbeatWire(config.stateGarbageCollectorHeartbeatPeriod())
                .solderTo(w.stateGarbageCollectorWiring().getInputWire(StateGarbageCollector::heartbeat), OFFER);
        w.model()
                .buildHeartbeatWire(config.signedStateSentinelHeartbeatPeriod())
                .solderTo(w.signedStateSentinelWiring().getInputWire(SignedStateSentinel::checkSignedStates), OFFER);

        // The state hasher needs to pass its data through a bunch of transformers. Construct those here.
        final OutputWire<ReservedSignedState> hashedStateOutputWire = w.stateHasherWiring()
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postHasher_stateReserver"));

        hashedStateOutputWire.solderTo(w.hashLoggerWiring().getInputWire(HashLogger::logHashes));
        hashedStateOutputWire.solderTo(w.stateSignerWiring().getInputWire(StateSigner::signState));
        hashedStateOutputWire.solderTo(w.issDetectorWiring().getInputWire(IssDetector::handleState));
        hashedStateOutputWire
                .buildTransformer("postHasher_notifier", "hashed states", StateHashedNotification::from)
                .solderTo(w.notifierWiring().getInputWire(AppNotifier::sendStateHashedNotification));

        // send state signatures to execution
        w.stateSignerWiring()
                .getOutputWire()
                .solderTo("ExecutionSignatureSubmission", "state signatures", execution::submitStateSignature);

        // FUTURE WORK: combine the signedStateHasherWiring State and Round outputs into a single StateAndRound output.
        // FUTURE WORK: Split the single StateAndRound output into separate State and Round wires.

        // Solder the state output as input to the state signature collector.
        hashedStateOutputWire.solderTo(
                w.stateSignatureCollectorWiring().getInputWire(StateSignatureCollector::addReservedState));

        w.stateSnapshotManagerWiring()
                .getTransformedOutput(StateSnapshotManager::extractOldestMinimumBirthRoundOnDisk)
                .solderTo(
                        w.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::setMinimumAncientIdentifierToStore),
                        INJECT);

        w.stateSnapshotManagerWiring()
                .getTransformedOutput(StateSnapshotManager::toStateWrittenToDiskAction)
                .solderTo(w.statusStateMachineWiring().getInputWire(StatusStateMachine::submitStatusAction));

        w.runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(w.transactionHandlerWiring().getInputWire(TransactionHandler::updateLegacyRunningEventHash));
        w.runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(w.consensusEventStreamWiring().getInputWire(ConsensusEventStream::legacyHashOverride));

        final OutputWire<IssNotification> splitIssDetectorOutput =
                w.issDetectorWiring().getSplitOutput();
        splitIssDetectorOutput.solderTo(w.issHandlerWiring().getInputWire(IssHandler::issObserved));
        w.issDetectorWiring()
                .getSplitAndTransformedOutput(IssDetector::getStatusAction)
                .solderTo(w.statusStateMachineWiring().getInputWire(StatusStateMachine::submitStatusAction));

        completeReservedSignedStatesWire.solderTo(w.latestCompleteStateNotifierWiring()
                .getInputWire(LatestCompleteStateNotifier::latestCompleteStateHandler));

        w.statusStateMachineWiring()
                .getOutputWire()
                .solderTo(w.eventCreationManagerWiring().getInputWire(EventCreationManager::updatePlatformStatus));
        w.statusStateMachineWiring()
                .getOutputWire()
                .solderTo(
                        w.consensusEngineWiring().componentWiring().getInputWire(ConsensusEngine::updatePlatformStatus),
                        INJECT);
        w.statusStateMachineWiring()
                .getOutputWire()
                .solderTo("ExecutionStatusHandler", "status updates", execution::newPlatformStatus);
        w.statusStateMachineWiring().getOutputWire().solderTo(w.gossipWiring().getPlatformStatusInput(), INJECT);

        solderNotifier(w);

        if (w.platformPublisherWiring().getSchedulerType() != NO_OP) {
            splitOrphanBufferOutput.solderTo(
                    w.platformPublisherWiring().getInputWire(PlatformPublisher::publishPreconsensusEvent));
        }

        buildUnsolderedWires(w);
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private static void solderEventWindow(PlatformComponents w) {
        final OutputWire<EventWindow> eventWindowOutputWire =
                w.eventWindowManagerWiring().getOutputWire();

        eventWindowOutputWire.solderTo(
                w.eventDeduplicatorWiring().getInputWire(EventDeduplicator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                w.eventSignatureValidatorWiring().getInputWire(EventSignatureValidator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(w.orphanBufferWiring().getInputWire(OrphanBuffer::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(w.gossipWiring().getEventWindowInput(), INJECT);
        eventWindowOutputWire.solderTo(
                w.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::updateNonAncientEventBoundary), INJECT);
        eventWindowOutputWire.solderTo(
                w.eventCreationManagerWiring().getInputWire(EventCreationManager::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                w.latestCompleteStateNexusWiring().getInputWire(LatestCompleteStateNexus::updateEventWindow));
        eventWindowOutputWire.solderTo(
                w.branchDetectorWiring().getInputWire(BranchDetector::updateEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                w.branchReporterWiring().getInputWire(BranchReporter::updateEventWindow), INJECT);
    }

    /**
     * Solder notifications into the notifier.
     */
    private static void solderNotifier(PlatformComponents w) {
        w.latestCompleteStateNotifierWiring()
                .getOutputWire()
                .solderTo(w.notifierWiring().getInputWire(AppNotifier::sendLatestCompleteStateNotification));
        w.stateSnapshotManagerWiring()
                .getTransformedOutput(StateSnapshotManager::toNotification)
                .solderTo(w.notifierWiring().getInputWire(AppNotifier::sendStateWrittenToDiskNotification), INJECT);

        final OutputWire<IssNotification> issNotificationOutputWire =
                w.issDetectorWiring().getSplitOutput();
        issNotificationOutputWire.solderTo(w.notifierWiring().getInputWire(AppNotifier::sendIssNotification));
        w.statusStateMachineWiring()
                .getOutputWire()
                .solderTo(w.notifierWiring().getInputWire(AppNotifier::sendPlatformStatusChangeNotification));
    }

    /**
     * {@link ComponentWiring} objects build their input wires when you first request them. Normally that happens when
     * we are soldering things together, but there are a few wires that aren't soldered and aren't used until later in
     * the lifecycle. This method forces those wires to be built.
     */
    private static void buildUnsolderedWires(PlatformComponents w) {
        w.eventDeduplicatorWiring().getInputWire(EventDeduplicator::clear);
        w.consensusEngineWiring().getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);
        if (w.platformPublisherWiring().getSchedulerType() != NO_OP) {
            w.platformPublisherWiring().getInputWire(PlatformPublisher::publishSnapshotOverride);
        }
        w.eventCreationManagerWiring().getInputWire(EventCreationManager::clear);
        w.notifierWiring().getInputWire(AppNotifier::sendReconnectCompleteNotification);
        w.notifierWiring().getInputWire(AppNotifier::sendPlatformStatusChangeNotification);
        w.eventSignatureValidatorWiring().getInputWire(EventSignatureValidator::updateRosterHistory);
        w.eventWindowManagerWiring().getInputWire(EventWindowManager::updateEventWindow);
        w.orphanBufferWiring().getInputWire(OrphanBuffer::clear);
        w.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::registerDiscontinuity);
        w.stateSignatureCollectorWiring().getInputWire(StateSignatureCollector::clear);
        w.issDetectorWiring().getInputWire(IssDetector::overridingState);
        w.issDetectorWiring().getInputWire(IssDetector::signalEndOfPreconsensusReplay);
        w.stateSnapshotManagerWiring().getInputWire(StateSnapshotManager::dumpStateTask);
        w.branchDetectorWiring().getInputWire(BranchDetector::clear);
        w.branchReporterWiring().getInputWire(BranchReporter::clear);
    }
}
