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
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.creator.EventCreationConfig;
import org.hiero.consensus.event.creator.EventCreatorModule;
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
            @NonNull final PlatformContext platformContext,
            @NonNull final ExecutionLayer execution,
            @NonNull final PlatformComponents components) {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(components);

        final InputWire<PlatformEvent> hasherInputWire =
                components.eventHasherWiring().getInputWire(EventHasher::hashEvent, "unhashed event");
        components.gossipWiring().getEventOutput().solderTo(hasherInputWire);

        components
                .gossipWiring()
                .getSyncLagOutput()
                .solderTo(components.eventCreationManagerWiring().getInputWire(EventCreatorModule::reportSyncRoundLag));

        components
                .eventHasherWiring()
                .getOutputWire()
                .solderTo(
                        components.internalEventValidatorWiring().getInputWire(InternalEventValidator::validateEvent));

        components
                .internalEventValidatorWiring()
                .getOutputWire()
                .solderTo(components.eventDeduplicatorWiring().getInputWire(EventDeduplicator::handleEvent));
        components
                .eventDeduplicatorWiring()
                .getOutputWire()
                .solderTo(components
                        .eventSignatureValidatorWiring()
                        .getInputWire(EventSignatureValidator::validateSignature));
        components
                .eventSignatureValidatorWiring()
                .getOutputWire()
                .solderTo(components.orphanBufferWiring().getInputWire(OrphanBuffer::handleEvent));
        final OutputWire<PlatformEvent> splitOrphanBufferOutput =
                components.orphanBufferWiring().getSplitOutput();

        splitOrphanBufferOutput.solderTo(
                components.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::writeEvent));

        // Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
        // reach consensus with events that might be lost due to a crash
        components
                .pcesInlineWriterWiring()
                .getOutputWire()
                .solderTo(components.consensusEngineWiring().componentWiring().getInputWire(ConsensusEngine::addEvent));

        // Make sure events are persisted before being gossipped. This prevents accidental branching in the case
        // where an event is created, gossipped, and then the node crashes before the event is persisted.
        // After restart, a node will not be aware of this event, so it can create a branch
        components
                .pcesInlineWriterWiring()
                .getOutputWire()
                .solderTo(components.gossipWiring().getEventInput(), INJECT);

        // Avoid using events as parents before they are persisted
        components
                .pcesInlineWriterWiring()
                .getOutputWire()
                .solderTo(components.eventCreationManagerWiring().getInputWire(EventCreatorModule::registerEvent));

        components
                .model()
                .getHealthMonitorWire()
                .solderTo(components
                        .eventCreationManagerWiring()
                        .getInputWire(EventCreatorModule::reportUnhealthyDuration));

        components
                .model()
                .getHealthMonitorWire()
                .solderTo(components.gossipWiring().getSystemHealthInput());
        components
                .model()
                .getHealthMonitorWire()
                .solderTo("executionHealthInput", "healthyDuration", execution::reportUnhealthyDuration);

        splitOrphanBufferOutput.solderTo(
                components.branchDetectorWiring().getInputWire(BranchDetector::checkForBranches));
        components
                .branchDetectorWiring()
                .getOutputWire()
                .solderTo(components.branchReporterWiring().getInputWire(BranchReporter::reportBranch));

        final double eventCreationHeartbeatFrequency = platformContext
                .getConfiguration()
                .getConfigData(EventCreationConfig.class)
                .creationAttemptRate();
        components
                .model()
                .buildHeartbeatWire(eventCreationHeartbeatFrequency)
                .solderTo(
                        components.eventCreationManagerWiring().getInputWire(EventCreatorModule::maybeCreateEvent),
                        OFFER);
        components
                .model()
                .buildHeartbeatWire(platformContext
                        .getConfiguration()
                        .getConfigData(PlatformStatusConfig.class)
                        .statusStateMachineHeartbeatPeriod())
                .solderTo(components.platformMonitorWiring().getInputWire(PlatformMonitor::heartbeat), OFFER);

        components
                .eventCreationManagerWiring()
                .getOutputWire()
                .solderTo(
                        components.internalEventValidatorWiring().getInputWire(InternalEventValidator::validateEvent),
                        INJECT);

        if (components.platformPublisherWiring().getSchedulerType() != NO_OP) {
            final OutputWire<PlatformEvent> staleEvent = components
                    .consensusEngineWiring()
                    .getOutputWire()
                    .buildTransformer("staleEvents", "consensusEngineOutput", ConsensusEngineOutput::staleEvents)
                    .buildSplitter("staleEventsSplitter", "stale events");
            staleEvent.solderTo(
                    components.platformPublisherWiring().getInputWire(PlatformPublisher::publishStaleEvent));
        }

        // an output wire that filters out only pre-consensus events from the consensus engine
        final OutputWire<PlatformEvent> consEngineAddedEvents = components
                .consensusEngineWiring()
                .getOutputWire()
                .buildTransformer(
                        "PreConsensusEvents", "consensusEngineOutput", ConsensusEngineOutput::preConsensusEvents)
                .buildSplitter("PreConsensusEventsSplitter", "preConsensusEvents");
        // pre-handle gets pre-consensus events from the consensus engine
        // the consensus engine ensures that all pre-consensus events either reach consensus of become stale
        consEngineAddedEvents.solderTo(components
                .applicationTransactionPrehandlerWiring()
                .getInputWire(TransactionPrehandler::prehandleApplicationTransactions));

        components
                .applicationTransactionPrehandlerWiring()
                .getOutputWire()
                .solderTo(components
                        .stateSignatureCollectorWiring()
                        .getInputWire(StateSignatureCollector::handlePreconsensusSignatures));

        // Split output of StateSignatureCollector into single ReservedSignedStates.
        final OutputWire<ReservedSignedState> splitReservedSignedStateWire = components
                .stateSignatureCollectorWiring()
                .getOutputWire()
                .buildSplitter("reservedStateSplitter", "reserved state lists");
        // Add another reservation to the signed states since we are soldering to two different input wires
        final OutputWire<ReservedSignedState> allReservedSignedStatesWire =
                splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));

        // Future work: this should be a full component in its own right or folded in with the state file manager.
        final WireFilter<ReservedSignedState> saveToDiskFilter =
                new WireFilter<>(components.model(), "saveToDiskFilter", "states", state -> {
                    if (state.get().isStateToSave()) {
                        return true;
                    }
                    state.close();
                    return false;
                });

        allReservedSignedStatesWire.solderTo(saveToDiskFilter.getInputWire());

        saveToDiskFilter
                .getOutputWire()
                .solderTo(components.stateSnapshotManagerWiring().getInputWire(StateSnapshotManager::saveStateTask));

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
                components.latestCompleteStateNexusWiring().getInputWire(LatestCompleteStateNexus::setStateIfNewer));

        solderEventWindow(components);

        components.pcesReplayerWiring().eventOutput().solderTo(hasherInputWire);

        final OutputWire<ConsensusRound> consensusRoundOutputWire = components
                .consensusEngineWiring()
                .consensusRoundsOutputWire()
                .buildSplitter("ConsensusRoundsSplitter", "consensus rounds");

        components
                .pcesReplayerWiring()
                .doneStreamingPcesOutputWire()
                .solderTo(components.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::beginStreamingNewEvents));
        // with inline PCES, the round bypasses the round durability buffer and goes directly to the round handler
        consensusRoundOutputWire.solderTo(
                components.transactionHandlerWiring().getInputWire(TransactionHandler::handleConsensusRound));

        consensusRoundOutputWire.solderTo(
                components.eventWindowManagerWiring().getInputWire(EventWindowManager::extractEventWindow));

        consensusRoundOutputWire
                .buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)
                .solderTo(components.consensusEventStreamWiring().getInputWire(ConsensusEventStream::addEvents));

        consensusRoundOutputWire.solderTo(
                components.platformMonitorWiring().getInputWire(PlatformMonitor::consensusRound));

        // The TransactionHandler output is split into two types: system transactions, and state with complexity.
        final OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                transactionHandlerSysTxnsOutputWire = components
                        .transactionHandlerWiring()
                        .getOutputWire()
                        .buildTransformer(
                                "getSystemTransactions",
                                "transaction handler result",
                                TransactionHandlerResult::systemTransactions);
        transactionHandlerSysTxnsOutputWire.solderTo(components
                .stateSignatureCollectorWiring()
                .getInputWire(StateSignatureCollector::handlePostconsensusSignatures));
        transactionHandlerSysTxnsOutputWire.solderTo(
                components.issDetectorWiring().getInputWire(IssDetector::handleStateSignatureTransactions));

        final OutputWire<StateWithHashComplexity> transactionHandlerStateWithComplexityOutput = components
                .transactionHandlerWiring()
                .getOutputWire()
                .buildFilter(
                        "notNullStateFilter",
                        "transaction handler result",
                        thr -> thr.stateWithHashComplexity() != null)
                .buildAdvancedTransformer(
                        new StateWithHashComplexityReserver("postHandler_stateWithHashComplexityReserver"));

        transactionHandlerStateWithComplexityOutput.solderTo(
                components.savedStateControllerWiring().getInputWire(SavedStateController::markSavedState));

        final OutputWire<ReservedSignedState> transactionHandlerStateOnlyOutput =
                transactionHandlerStateWithComplexityOutput.buildAdvancedTransformer(
                        new StateWithHashComplexityToStateReserver(
                                "postHandler_stateWithHashComplexityToStateReserver"));

        transactionHandlerStateOnlyOutput.solderTo(
                components.latestImmutableStateNexusWiring().getInputWire(SignedStateNexus::setState));
        transactionHandlerStateOnlyOutput.solderTo(
                components.stateGarbageCollectorWiring().getInputWire(StateGarbageCollector::registerState));

        components
                .savedStateControllerWiring()
                .getOutputWire()
                .solderTo(components.stateHasherWiring().getInputWire(StateHasher::hashState));

        final var config = platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);
        components
                .model()
                .buildHeartbeatWire(config.stateGarbageCollectorHeartbeatPeriod())
                .solderTo(
                        components.stateGarbageCollectorWiring().getInputWire(StateGarbageCollector::heartbeat), OFFER);
        components
                .model()
                .buildHeartbeatWire(config.signedStateSentinelHeartbeatPeriod())
                .solderTo(
                        components.signedStateSentinelWiring().getInputWire(SignedStateSentinel::checkSignedStates),
                        OFFER);

        // The state hasher needs to pass its data through a bunch of transformers. Construct those here.
        final OutputWire<ReservedSignedState> hashedStateOutputWire = components
                .stateHasherWiring()
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postHasher_stateReserver"));

        hashedStateOutputWire.solderTo(components.hashLoggerWiring().getInputWire(HashLogger::logHashes));
        hashedStateOutputWire.solderTo(components.stateSignerWiring().getInputWire(StateSigner::signState));
        hashedStateOutputWire.solderTo(components.issDetectorWiring().getInputWire(IssDetector::handleState));
        hashedStateOutputWire
                .buildTransformer("postHasher_notifier", "hashed states", StateHashedNotification::from)
                .solderTo(components.notifierWiring().getInputWire(AppNotifier::sendStateHashedNotification));

        // send state signatures to execution
        components
                .stateSignerWiring()
                .getOutputWire()
                .solderTo("ExecutionSignatureSubmission", "state signatures", execution::submitStateSignature);

        // FUTURE WORK: combine the signedStateHasherWiring State and Round outputs into a single StateAndRound output.
        // FUTURE WORK: Split the single StateAndRound output into separate State and Round wires.

        // Solder the state output as input to the state signature collector.
        hashedStateOutputWire.solderTo(
                components.stateSignatureCollectorWiring().getInputWire(StateSignatureCollector::addReservedState));

        components
                .stateSnapshotManagerWiring()
                .getTransformedOutput(StateSnapshotManager::extractOldestMinimumBirthRoundOnDisk)
                .solderTo(
                        components
                                .pcesInlineWriterWiring()
                                .getInputWire(InlinePcesWriter::setMinimumAncientIdentifierToStore),
                        INJECT);

        components
                .stateSnapshotManagerWiring()
                .getOutputWire()
                .solderTo(components.platformMonitorWiring().getInputWire(PlatformMonitor::stateWrittenToDisk));

        components
                .runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(components
                        .transactionHandlerWiring()
                        .getInputWire(TransactionHandler::updateLegacyRunningEventHash));
        components
                .runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(
                        components.consensusEventStreamWiring().getInputWire(ConsensusEventStream::legacyHashOverride));

        final OutputWire<IssNotification> splitIssDetectorOutput =
                components.issDetectorWiring().getSplitOutput();
        splitIssDetectorOutput.solderTo(components.issHandlerWiring().getInputWire(IssHandler::issObserved));
        components
                .issDetectorWiring()
                .getOutputWire()
                .solderTo(components.platformMonitorWiring().getInputWire(PlatformMonitor::issNotification));

        completeReservedSignedStatesWire.solderTo(components
                .latestCompleteStateNotifierWiring()
                .getInputWire(LatestCompleteStateNotifier::latestCompleteStateHandler));

        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(
                        components.eventCreationManagerWiring().getInputWire(EventCreatorModule::updatePlatformStatus));
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(
                        components.consensusEngineWiring().getInputWire(ConsensusEngine::updatePlatformStatus), INJECT);
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo("ExecutionStatusHandler", "status updates", execution::newPlatformStatus);
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(components.gossipWiring().getPlatformStatusInput(), INJECT);

        solderNotifier(components);

        if (components.platformPublisherWiring().getSchedulerType() != NO_OP) {
            splitOrphanBufferOutput.solderTo(
                    components.platformPublisherWiring().getInputWire(PlatformPublisher::publishPreconsensusEvent));
        }

        buildUnsolderedWires(components);
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private static void solderEventWindow(final PlatformComponents components) {
        final OutputWire<EventWindow> eventWindowOutputWire =
                components.eventWindowManagerWiring().getOutputWire();

        eventWindowOutputWire.solderTo(
                components.eventDeduplicatorWiring().getInputWire(EventDeduplicator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                components.eventSignatureValidatorWiring().getInputWire(EventSignatureValidator::setEventWindow),
                INJECT);
        eventWindowOutputWire.solderTo(
                components.orphanBufferWiring().getInputWire(OrphanBuffer::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(components.gossipWiring().getEventWindowInput(), INJECT);
        eventWindowOutputWire.solderTo(
                components.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::updateNonAncientEventBoundary),
                INJECT);
        eventWindowOutputWire.solderTo(
                components.eventCreationManagerWiring().getInputWire(EventCreatorModule::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                components.latestCompleteStateNexusWiring().getInputWire(LatestCompleteStateNexus::updateEventWindow));
        eventWindowOutputWire.solderTo(
                components.branchDetectorWiring().getInputWire(BranchDetector::updateEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                components.branchReporterWiring().getInputWire(BranchReporter::updateEventWindow), INJECT);
    }

    /**
     * Solder notifications into the notifier.
     */
    private static void solderNotifier(final PlatformComponents components) {
        components
                .latestCompleteStateNotifierWiring()
                .getOutputWire()
                .solderTo(components.notifierWiring().getInputWire(AppNotifier::sendLatestCompleteStateNotification));
        components
                .stateSnapshotManagerWiring()
                .getTransformedOutput(StateSnapshotManager::toNotification)
                .solderTo(
                        components.notifierWiring().getInputWire(AppNotifier::sendStateWrittenToDiskNotification),
                        INJECT);

        final OutputWire<IssNotification> issNotificationOutputWire =
                components.issDetectorWiring().getSplitOutput();
        issNotificationOutputWire.solderTo(components.notifierWiring().getInputWire(AppNotifier::sendIssNotification));
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(components.notifierWiring().getInputWire(AppNotifier::sendPlatformStatusChangeNotification));
    }

    /**
     * {@link ComponentWiring} objects build their input wires when you first request them. Normally that happens when
     * we are soldering things together, but there are a few wires that aren't soldered and aren't used until later in
     * the lifecycle. This method forces those wires to be built.
     */
    private static void buildUnsolderedWires(final PlatformComponents components) {
        components.eventDeduplicatorWiring().getInputWire(EventDeduplicator::clear);
        components.consensusEngineWiring().getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);
        if (components.platformPublisherWiring().getSchedulerType() != NO_OP) {
            components.platformPublisherWiring().getInputWire(PlatformPublisher::publishSnapshotOverride);
        }
        components.eventCreationManagerWiring().getInputWire(EventCreatorModule::clear);
        components.notifierWiring().getInputWire(AppNotifier::sendReconnectCompleteNotification);
        components.notifierWiring().getInputWire(AppNotifier::sendPlatformStatusChangeNotification);
        components.eventSignatureValidatorWiring().getInputWire(EventSignatureValidator::updateRosterHistory);
        components.eventWindowManagerWiring().getInputWire(EventWindowManager::updateEventWindow);
        components.orphanBufferWiring().getInputWire(OrphanBuffer::clear);
        components.pcesInlineWriterWiring().getInputWire(InlinePcesWriter::registerDiscontinuity);
        components.stateSignatureCollectorWiring().getInputWire(StateSignatureCollector::clear);
        components.issDetectorWiring().getInputWire(IssDetector::overridingState);
        components.issDetectorWiring().getInputWire(IssDetector::signalEndOfPreconsensusReplay);
        components.stateSnapshotManagerWiring().getInputWire(StateSnapshotManager::dumpStateTask);
        components.branchDetectorWiring().getInputWire(BranchDetector::clear);
        components.branchReporterWiring().getInputWire(BranchReporter::clear);
        components.platformMonitorWiring().getInputWire(PlatformMonitor::submitStatusAction);
        components.platformMonitorWiring().getInputWire(PlatformMonitor::quiescenceCommand);
        components.eventCreationManagerWiring().getInputWire(EventCreatorModule::quiescenceCommand);
    }
}
