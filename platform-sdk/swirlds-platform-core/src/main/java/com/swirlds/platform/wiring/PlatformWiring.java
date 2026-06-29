// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static com.swirlds.component.framework.wires.SolderType.OFFER;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.transformers.WireFilter;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.system.StaleEventConsumer;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
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
            @Nullable final StaleEventConsumer staleEventConsumer) {
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

        if (staleEventConsumer != null) {
            final OutputWire<PlatformEvent> staleEvent =
                    components.hashgraphModule().staleEventOutputWire();
            staleEvent.solderTo("staleEventCallback", "stale events", staleEventConsumer::processStaleEvent);
        }

        // an output wire that filters out only pre-consensus events from the consensus engine
        final OutputWire<PlatformEvent> consEngineAddedEvents =
                components.hashgraphModule().preconsensusEventOutputWire();
        // pre-handle gets pre-consensus events from the consensus engine
        // the consensus engine ensures that all pre-consensus events either reach consensus of become stale
        consEngineAddedEvents.solderTo(components.transactionHandlingModule().preHandleEventInputWire());

        components
                .transactionHandlingModule()
                .preHandleSignaturesOutputWire()
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
                splitReservedSignedStateWire.buildAdvancedTransformer(
                        new org.hiero.consensus.state.management.utils.SignedStateReserver("allStatesReserver"));

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
                .pcesEventsToReplay()
                .solderTo(components.eventIntakeModule().unhashedEventsInputWire());

        final OutputWire<ConsensusRound> consensusRoundOutputWire =
                components.hashgraphModule().consensusRoundOutputWire();

        // with inline PCES, the round bypasses the round durability buffer and goes directly to the round handler
        consensusRoundOutputWire.solderTo(components.transactionHandlingModule().handleConsensusRoundInputWire());

        consensusRoundOutputWire.solderTo(
                components.eventWindowManagerWiring().getInputWire(EventWindowManager::extractEventWindow));

        consensusRoundOutputWire
                .buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)
                .solderTo(components.consensusEventStreamWiring().getInputWire(ConsensusEventStream::addEvents));

        consensusRoundOutputWire.solderTo(
                components.platformMonitorWiring().getInputWire(PlatformMonitor::consensusRound));

        components
                .transactionHandlingModule()
                .handleSignaturesOutputWire()
                .solderTo(components
                        .stateSignatureCollectorWiring()
                        .getInputWire(StateSignatureCollector::handlePostconsensusSignatures));
        components
                .transactionHandlingModule()
                .handleSignaturesOutputWire()
                .solderTo(components.issDetectionModule().systemTransactionsInputWire());

        components
                .transactionHandlingModule()
                .stateWithHashComplexityOutputWire()
                .solderTo(components.savedStateControllerWiring().getInputWire(SavedStateController::markSavedState));

        components
                .transactionHandlingModule()
                .stateOutputWire()
                .solderTo(components.latestImmutableStateNexusWiring().getInputWire(SignedStateNexus::setState));
        components
                .transactionHandlingModule()
                .stateOutputWire()
                .solderTo(components.stateGarbageCollectorWiring().getInputWire(StateGarbageCollector::registerState));

        components
                .savedStateControllerWiring()
                .getOutputWire()
                .solderTo(components.stateManagementModule().unhashedStatesInputWire());

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

        final OutputWire<ReservedSignedState> hashedStateOutputWire =
                components.stateManagementModule().hashedStateOutputWire();
        hashedStateOutputWire.solderTo(components.issDetectionModule().stateInputWire());
        hashedStateOutputWire
                .buildTransformer("postHasher_notifier", "hashed states", StateHashedNotification::from)
                .solderTo(components.notifierWiring().getInputWire(AppNotifier::sendStateHashedNotification));

        // send state signatures to execution
        components
                .stateManagementModule()
                .stateSignaturesOutputWire()
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
                .solderTo(components.transactionHandlingModule().hashOverrideInputWire());
        components
                .runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(
                        components.consensusEventStreamWiring().getInputWire(ConsensusEventStream::legacyHashOverride));

        components
                .issDetectionModule()
                .issNotificationOutputWire()
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
        components
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(
                        components
                                .latestCompleteStateNexusWiring()
                                .getInputWire(LatestCompleteStateNexus::updatePlatformStatus),
                        INJECT);

        solderNotifier(components);
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

        components
                .issDetectionModule()
                .issNotificationOutputWire()
                .solderTo(components.notifierWiring().getInputWire(AppNotifier::sendIssNotification));
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
        components.stateSnapshotManagerWiring().getInputWire(StateSnapshotManager::dumpStateTask);
        components.platformMonitorWiring().getInputWire(PlatformMonitor::submitStatusAction);
        components.platformMonitorWiring().getInputWire(PlatformMonitor::quiescenceCommand);
    }
}
