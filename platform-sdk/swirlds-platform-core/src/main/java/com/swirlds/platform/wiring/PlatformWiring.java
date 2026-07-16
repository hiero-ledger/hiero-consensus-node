// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static com.swirlds.component.framework.wires.SolderType.OFFER;

import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Encapsulates wiring for {@link com.swirlds.platform.SwirldsPlatform}.
 */
public class PlatformWiring {

    /**
     * Wire the components together.
     */
    public static void wire(
            @NonNull final ConsensusLayerInputs inputs, @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(buildingBlocks);

        buildingBlocks
                .gossipModule()
                .receivedEventOutputWire()
                .solderTo(buildingBlocks.eventIntakeModule().unhashedEventsInputWire());

        buildingBlocks
                .gossipModule()
                .syncProgressOutputWire()
                .solderTo(buildingBlocks.eventCreatorModule().syncProgressInputWire());

        // Note: This is an intermediate step while migrating components to the new event intake module.
        // Right now, the output wire does not provide validated events, but events that have only
        // run through the components that have been migrated so far.
        buildingBlocks
                .eventIntakeModule()
                .validatedEventsOutputWire()
                .solderTo(buildingBlocks.pcesModule().eventsToWriteInputWire());

        final OutputWire<PlatformEvent> writtenEventOutputWire =
                buildingBlocks.pcesModule().writtenEventsOutputWire();

        // Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
        // reach consensus with events that might be lost due to a crash
        writtenEventOutputWire.solderTo(buildingBlocks.hashgraphModule().eventInputWire());

        // Make sure events are persisted before being gossipped. This prevents accidental branching in the case
        // where an event is created, gossipped, and then the node crashes before the event is persisted.
        // After restart, a node will not be aware of this event, so it can create a branch
        writtenEventOutputWire.solderTo(buildingBlocks.gossipModule().eventToGossipInputWire(), INJECT);

        // Avoid using events as parents before they are persisted
        writtenEventOutputWire.solderTo(buildingBlocks.eventCreatorModule().orderedEventInputWire());

        buildingBlocks
                .wiringModel()
                .getHealthMonitorWire()
                .solderTo(buildingBlocks.eventCreatorModule().healthStatusInputWire());

        buildingBlocks
                .wiringModel()
                .getHealthMonitorWire()
                .solderTo(buildingBlocks.gossipModule().healthStatusInputWire());
        buildingBlocks
                .wiringModel()
                .getHealthMonitorWire()
                .solderTo("executionHealthInput", "healthyDuration", inputs.executionLayer()::reportUnhealthyDuration);

        buildingBlocks
                .wiringModel()
                .buildHeartbeatWire(inputs.configuration()
                        .getConfigData(PlatformStatusConfig.class)
                        .statusStateMachineHeartbeatPeriod())
                .solderTo(buildingBlocks.platformMonitorWiring().getInputWire(PlatformMonitor::heartbeat), OFFER);

        buildingBlocks
                .eventCreatorModule()
                .createdEventOutputWire()
                .solderTo(buildingBlocks.eventIntakeModule().nonValidatedEventsInputWire(), INJECT);

        if (inputs.staleEventConsumer() != null) {
            final OutputWire<PlatformEvent> staleEvent =
                    buildingBlocks.hashgraphModule().staleEventOutputWire();
            staleEvent.solderTo("staleEventCallback", "stale events", inputs.staleEventConsumer()::processStaleEvent);
        }

        // an output wire that filters out only pre-consensus events from the consensus engine
        final OutputWire<PlatformEvent> consEngineAddedEvents =
                buildingBlocks.hashgraphModule().preconsensusEventOutputWire();
        // pre-handle gets pre-consensus events from the consensus engine
        // the consensus engine ensures that all pre-consensus events either reach consensus of become stale
        consEngineAddedEvents.solderTo(
                buildingBlocks.transactionHandlingModule().preHandleEventInputWire());

        buildingBlocks
                .transactionHandlingModule()
                .preHandleSignaturesOutputWire()
                .solderTo(buildingBlocks.stateModule().preconsensusSystemTransactionsInputWire());

        solderEventWindow(buildingBlocks);

        buildingBlocks
                .pcesModule()
                .pcesEventsToReplay()
                .solderTo(buildingBlocks.eventIntakeModule().unhashedEventsInputWire());

        final OutputWire<ConsensusRound> consensusRoundOutputWire =
                buildingBlocks.hashgraphModule().consensusRoundOutputWire();

        // with inline PCES, the round bypasses the round durability buffer and goes directly to the round handler
        consensusRoundOutputWire.solderTo(
                buildingBlocks.transactionHandlingModule().handleConsensusRoundInputWire());

        consensusRoundOutputWire.solderTo(
                buildingBlocks.eventWindowManagerWiring().getInputWire(EventWindowManager::extractEventWindow));

        consensusRoundOutputWire
                .buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)
                .solderTo(buildingBlocks.consensusEventStreamWiring().getInputWire(ConsensusEventStream::addEvents));

        consensusRoundOutputWire.solderTo(
                buildingBlocks.platformMonitorWiring().getInputWire(PlatformMonitor::consensusRound));

        buildingBlocks
                .transactionHandlingModule()
                .handleSignaturesOutputWire()
                .solderTo(buildingBlocks.stateModule().postconsensusSystemTranscationsInputWire());
        buildingBlocks
                .transactionHandlingModule()
                .handleSignaturesOutputWire()
                .solderTo(buildingBlocks.issDetectionModule().systemTransactionsInputWire());

        buildingBlocks
                .transactionHandlingModule()
                .stateWithHashComplexityOutputWire()
                .solderTo(buildingBlocks.stateModule().unhashedStatesInputWire());

        buildingBlocks
                .transactionHandlingModule()
                .stateOutputWire()
                .solderTo(buildingBlocks.stateModule().garbageCollectorRegistrationInputWire());

        final OutputWire<ReservedSignedState> hashedStateOutputWire =
                buildingBlocks.stateModule().hashedStateOutputWire();
        hashedStateOutputWire.solderTo(buildingBlocks.issDetectionModule().stateInputWire());
        hashedStateOutputWire
                .buildTransformer("postHasher_notifier", "hashed states", StateHashedNotification::from)
                .solderTo(buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendStateHashedNotification));

        // send state signatures to execution
        buildingBlocks
                .stateModule()
                .stateSignaturesOutputWire()
                .solderTo(
                        "ExecutionSignatureSubmission",
                        "state signatures",
                        inputs.executionLayer()::submitStateSignature);

        buildingBlocks
                .stateModule()
                .oldestMinimumBirthRoundOnDiskOutputWire()
                .solderTo(buildingBlocks.pcesModule().minimumBirthRoundInputWire(), INJECT);

        buildingBlocks
                .stateModule()
                .stateSavingResultOutputWire()
                .solderTo(buildingBlocks.platformMonitorWiring().getInputWire(PlatformMonitor::stateWrittenToDisk));

        buildingBlocks
                .runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(buildingBlocks.transactionHandlingModule().hashOverrideInputWire());
        buildingBlocks
                .runningEventHashOverrideWiring()
                .runningHashUpdateOutput()
                .solderTo(buildingBlocks
                        .consensusEventStreamWiring()
                        .getInputWire(ConsensusEventStream::legacyHashOverride));

        buildingBlocks
                .issDetectionModule()
                .issNotificationOutputWire()
                .solderTo(buildingBlocks.platformMonitorWiring().getInputWire(PlatformMonitor::issNotification));

        buildingBlocks
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(buildingBlocks.eventCreatorModule().platformStatusInputWire());
        buildingBlocks
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(buildingBlocks.hashgraphModule().platformStatusInputWire(), INJECT);
        buildingBlocks
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo("ExecutionStatusHandler", "status updates", inputs.executionLayer()::newPlatformStatus);
        buildingBlocks
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(buildingBlocks.gossipModule().platformStatusInputWire(), INJECT);
        buildingBlocks
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(buildingBlocks.stateModule().platformStatusInputWire(), INJECT);

        solderNotifier(buildingBlocks);
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private static void solderEventWindow(final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<EventWindow> eventWindowOutputWire =
                buildingBlocks.eventWindowManagerWiring().getOutputWire();

        eventWindowOutputWire.solderTo(buildingBlocks.eventIntakeModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.gossipModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.pcesModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.eventCreatorModule().eventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.stateModule().eventWindowInputWire());
    }

    /**
     * Solder notifications into the notifier.
     */
    private static void solderNotifier(final ConsensusLayerBuildingBlocks buildingBlocks) {
        buildingBlocks
                .stateModule()
                .stateSavingResultOutputWire()
                .buildTransformer(
                        "stateSavedNotifier",
                        "state saved results",
                        result -> new StateWriteToDiskCompleteNotification(
                                result.round(), result.consensusTimestamp(), result.freezeState()))
                .solderTo(
                        buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendStateWrittenToDiskNotification),
                        INJECT);

        buildingBlocks
                .issDetectionModule()
                .issNotificationOutputWire()
                .solderTo(buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendIssNotification));
        buildingBlocks
                .platformMonitorWiring()
                .getOutputWire()
                .solderTo(buildingBlocks
                        .notifierWiring()
                        .getInputWire(AppNotifier::sendPlatformStatusChangeNotification));
    }
}
