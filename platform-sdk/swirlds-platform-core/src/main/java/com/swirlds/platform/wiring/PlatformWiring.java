// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static com.swirlds.component.framework.wires.SolderType.OFFER;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.transformers.WireFilter;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionHandlerResult;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.StateGarbageCollector;

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
            @NonNull final PlatformComponents components,
            @NonNull final ApplicationCallbacks callbacks) {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(components);

        components
                .gossipModule()
                .receivedEventOutputWire()
                .solderTo(components.eventIntakeModule().unhashedEventsInputWire());

        components
                .gossipModule()
                .syncProgressOutputWire()
                .solderTo(components.eventCreatorModule().syncProgressInputWire());

        // Note: This is an intermediate step while migrating components to the new event intake module.
        // Right now, the output wire does not provide validated events, but events that have only
        // run through the components that have been migrated so far.
        components
                .eventIntakeModule()
                .validatedEventsOutputWire()
                .solderTo(components.pcesModule().eventsToWriteInputWire());

        final OutputWire<PlatformEvent> writtenEventOutputWire =
                components.pcesModule().writtenEventsOutputWire();

        // Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
        // reach consensus with events that might be lost due to a crash
        writtenEventOutputWire.solderTo(components.hashgraphModule().eventInputWire());

        // Make sure events are persisted before being gossipped. This prevents accidental branching in the case
        // where an event is created, gossipped, and then the node crashes before the event is persisted.
        // After restart, a node will not be aware of this event, so it can create a branch
        writtenEventOutputWire.solderTo(components.gossipModule().eventToGossipInputWire(), INJECT);

        // Avoid using events as parents before they are persisted
        writtenEventOutputWire.solderTo(components.eventCreatorModule().orderedEventInputWire());

        components
                .model()
                .getHealthMonitorWire()
                .solderTo(components.eventCreatorModule().healthStatusInputWire());

        components
                .model()
                .getHealthMonitorWire()
                .solderTo(components.gossipModule().healthStatusInputWire());
        components
                .model()
                .getHealthMonitorWire()
                .solderTo("executionHealthInput", "healthyDuration", execution::reportUnhealthyDuration);

        components
                .eventIntakeModule()
                .validatedEventsOutputWire()
                .solderTo(components.branchDetectorWiring().getInputWire(BranchDetector::checkForBranches));
        components
                .branchDetectorWiring()
                .getOutputWire()
                .solderTo(components.branchReporterWiring().getInputWire(BranchReporter::reportBranch));

        components
                .model()
                .buildHeartbeatWire(platformContext
                        .getConfiguration()
                        .getConfigData(PlatformStatusConfig.class)
                        .statusStateMachineHeartbeatPeriod())
                .solderTo(components.platformMonitorWiring().getInputWire(PlatformMonitor::heartbeat), OFFER);

        components
                .eventCreatorModule()
                .createdEventOutputWire()
                .solderTo(components.eventIntakeModule().nonValidatedEventsInputWire(), INJECT);

        if (callbacks.staleEventConsumer() != null) {
            final OutputWire<PlatformEvent> staleEvent =
                    components.hashgraphModule().staleEventOutputWire();
            staleEvent.solderTo("staleEventCallback", "stale events", callbacks.staleEventConsumer());
        }

        // an output wire that filters out only pre-consensus events from the consensus engine
        final OutputWire<PlatformEvent> consEngineAddedEvents =
                components.hashgraphModule().preconsensusEventOutputWire();
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

        // Filter to complete states only
        final OutputWire<ReservedSignedState> completeReservedSignedStatesWire =
                allReservedSignedStatesWire.buildFilter("completeStateFilter", "states", rs -> {
                    if (rs.get().isComplete()) {
                        return true;
                    } else {
                        // close the second reservation on states that are not passed on.
                        rs.close();
                        return false;
                    }
                });
        completeReservedSignedStatesWire.solderTo(
                components.latestCompleteStateNexusWiring().getInputWire(LatestCompleteStateNexus::setStateIfNewer));

        solderEventWindow(components);

        components
                .pcesModule()
                .replayedEventsOutputWire()
                .solderTo(components.eventIntakeModule().unhashedEventsInputWire());

        final OutputWire<ConsensusRound> consensusRoundOutputWire =
                components.hashgraphModule().consensusRoundOutputWire();

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
                .solderTo(components.pcesModule().minimumBirthRoundInputWire(), INJECT);

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

        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(components.eventCreatorModule().platformStatusInputWire());
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(components.hashgraphModule().platformStatusInputWire(), INJECT);
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo("ExecutionStatusHandler", "status updates", execution::newPlatformStatus);
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(components.gossipModule().platformStatusInputWire(), INJECT);

        solderNotifier(components);

        if (callbacks.preconsensusEventConsumer() != null) {
            components
                    .eventIntakeModule()
                    .validatedEventsOutputWire()
                    .solderTo(
                            "preConsensusEventCallback", "pre-consensus events", callbacks.preconsensusEventConsumer());
        }

        buildUnsolderedWires(components);
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private static void solderEventWindow(final PlatformComponents components) {
        final OutputWire<EventWindow> eventWindowOutputWire =
                components.eventWindowManagerWiring().getOutputWire();

        eventWindowOutputWire.solderTo(components.eventIntakeModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(components.gossipModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(components.pcesModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(components.eventCreatorModule().eventWindowInputWire(), INJECT);
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
        components.notifierWiring().getInputWire(AppNotifier::sendReconnectCompleteNotification);
        components.notifierWiring().getInputWire(AppNotifier::sendPlatformStatusChangeNotification);
        components.eventWindowManagerWiring().getInputWire(EventWindowManager::updateEventWindow);
        components.stateSignatureCollectorWiring().getInputWire(StateSignatureCollector::clear);
        components.issDetectorWiring().getInputWire(IssDetector::overridingState);
        components.issDetectorWiring().getInputWire(IssDetector::signalEndOfPreconsensusReplay);
        components.stateSnapshotManagerWiring().getInputWire(StateSnapshotManager::dumpStateTask);
        components.branchDetectorWiring().getInputWire(BranchDetector::clear);
        components.branchReporterWiring().getInputWire(BranchReporter::clear);
        components.platformMonitorWiring().getInputWire(PlatformMonitor::submitStatusAction);
        components.platformMonitorWiring().getInputWire(PlatformMonitor::quiescenceCommand);
    }
}
