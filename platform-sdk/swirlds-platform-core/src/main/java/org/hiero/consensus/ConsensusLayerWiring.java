// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static org.hiero.consensus.wiring.framework.wires.SolderType.INJECT;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;

/**
 * Encapsulates wiring for {@link com.swirlds.platform.SwirldsPlatform}.
 *
 * <p>{@link #wire(ConsensusLayerInputs, ConsensusLayerBuildingBlocks)} reads as a table of contents: one
 * {@code wireXxxOutputs} method per producing component, each soldering that component's output wires to every
 * consumer. To find where a component's output goes, open the method named after it.
 * <p>Methods taking ConsensusLayerInputs are the ones that wire into the execution layer / external consumers.
 */
public class ConsensusLayerWiring {

    private ConsensusLayerWiring() {}

    /**
     * Wire the components together.
     *
     * @param inputs the inputs to the consensus layer
     * @param buildingBlocks the building blocks of the consensus layer
     */
    public static void wire(
            @NonNull final ConsensusLayerInputs inputs, @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(buildingBlocks);

        wireGossipOutputs(buildingBlocks);
        wireEventIntakeOutputs(buildingBlocks);
        wireEventCreatorOutputs(buildingBlocks);
        wirePcesOutputs(buildingBlocks);
        wireHashgraphOutputs(inputs, buildingBlocks);
        wireInitialEventWindowDispatcher(buildingBlocks);
        wireTransactionHandlingOutputs(buildingBlocks);
        wireStateOutputs(inputs, buildingBlocks);
        wireIssDetectionOutputs(buildingBlocks);
        wireRunningHashOverrideOutputs(buildingBlocks);
        wirePlatformMonitorOutputs(inputs, buildingBlocks);
        wireInfrastructure(inputs, buildingBlocks);
    }

    /**
     * Solder the gossip module's outputs to their consumers.
     */
    private static void wireGossipOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final GossipModule gossip = buildingBlocks.gossipModule();

        gossip.receivedEventOutputWire()
                .solderTo(buildingBlocks.eventIntakeModule().unhashedEventsInputWire());
        gossip.syncProgressOutputWire()
                .solderTo(buildingBlocks.eventCreatorModule().syncProgressInputWire());
    }

    /**
     * Solder the event intake module's outputs to their consumers.
     */
    private static void wireEventIntakeOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        buildingBlocks
                .eventIntakeModule()
                .validatedEventsOutputWire()
                .solderTo(buildingBlocks.pcesModule().eventsToWriteInputWire());
    }

    /**
     * Solder the event creator module's outputs to their consumers.
     */
    private static void wireEventCreatorOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        buildingBlocks
                .eventCreatorModule()
                .createdEventOutputWire()
                .solderTo(buildingBlocks.eventIntakeModule().nonValidatedEventsInputWire(), INJECT);
    }

    /**
     * Solder the PCES module's outputs to their consumers.
     */
    private static void wirePcesOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final PcesModule pcesModule = buildingBlocks.pcesModule();

        final OutputWire<PlatformEvent> writtenEventOutputWire = pcesModule.writtenEventsOutputWire();

        // Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
        // reach consensus with events that might be lost due to a crash
        writtenEventOutputWire.solderTo(buildingBlocks.hashgraphModule().eventInputWire());

        // Make sure events are persisted before being gossipped. This prevents accidental branching in the case
        // where an event is created, gossipped, and then the node crashes before the event is persisted.
        // After restart, a node will not be aware of this event, so it can create a branch
        writtenEventOutputWire.solderTo(buildingBlocks.gossipModule().eventToGossipInputWire(), INJECT);

        // Avoid using events as parents before they are persisted
        writtenEventOutputWire.solderTo(buildingBlocks.eventCreatorModule().orderedEventInputWire());

        pcesModule
                .pcesEventsToReplay()
                .solderTo(buildingBlocks.eventIntakeModule().unhashedEventsInputWire());
    }

    /**
     * Solder the hashgraph (consensus engine) module's outputs to their consumers.
     */
    private static void wireHashgraphOutputs(
            @NonNull final ConsensusLayerInputs inputs, @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final HashgraphModule hashgraph = buildingBlocks.hashgraphModule();

        final OutputWire<PlatformEvent> staleEvent = hashgraph.staleEventOutputWire();
        staleEvent.solderTo("staleEventCallback", "stale events", inputs.staleEventConsumer()::processStaleEvent);

        final OutputWire<PlatformEvent> preconsensusEventOutputWire = hashgraph.preconsensusEventOutputWire();

        // pre-handle gets pre-consensus events from the consensus engine
        // the consensus engine ensures that all pre-consensus events either reach consensus of become stale
        preconsensusEventOutputWire.solderTo(
                buildingBlocks.transactionHandlingModule().preHandleEventInputWire());

        final OutputWire<ConsensusRound> consensusRoundOutputWire = hashgraph.consensusRoundOutputWire();

        consensusRoundOutputWire.solderTo(buildingBlocks.eventIntakeModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.gossipModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.pcesModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.eventCreatorModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.stateModule().consensusRoundInputWire(), INJECT);

        consensusRoundOutputWire.solderTo(
                buildingBlocks.transactionHandlingModule().handleConsensusRoundInputWire());

        consensusRoundOutputWire
                .buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)
                .solderTo(buildingBlocks.consensusEventStreamWiring().getInputWire(ConsensusEventStream::addEvents));

        consensusRoundOutputWire.solderTo(buildingBlocks.statusMonitorModule().consensusRoundInputWire());
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private static void wireInitialEventWindowDispatcher(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<EventWindow> eventWindowOutputWire =
                buildingBlocks.initialEventWindowDispatcher().getOutputWire();

        eventWindowOutputWire.solderTo(buildingBlocks.eventIntakeModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.gossipModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.pcesModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.eventCreatorModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.stateModule().initialEventWindowInputWire());
    }

    /**
     * Solder the transaction handling module's outputs to their consumers.
     */
    private static void wireTransactionHandlingOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final TransactionHandlingModule txnHandling = buildingBlocks.transactionHandlingModule();
        final StateModule state = buildingBlocks.stateModule();

        txnHandling.preHandleSignaturesOutputWire().solderTo(state.preconsensusSystemTransactionsInputWire());

        final OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>> handleSignatures =
                txnHandling.handleSignaturesOutputWire();
        handleSignatures.solderTo(state.postconsensusSystemTranscationsInputWire());
        handleSignatures.solderTo(buildingBlocks.issDetectionModule().systemTransactionsInputWire());

        txnHandling.stateWithHashComplexityOutputWire().solderTo(state.unhashedStatesInputWire());

        txnHandling.stateOutputWire().solderTo(state.garbageCollectorRegistrationInputWire());
    }

    /**
     * Solder the state module's outputs to their consumers.
     */
    private static void wireStateOutputs(
            @NonNull final ConsensusLayerInputs inputs, @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final StateModule state = buildingBlocks.stateModule();

        final OutputWire<ReservedSignedState> hashedStateOutputWire = state.hashedStateOutputWire();
        hashedStateOutputWire.solderTo(buildingBlocks.issDetectionModule().stateInputWire());
        hashedStateOutputWire
                .buildTransformer("postHasher_notifier", "hashed states", StateHashedNotification::from)
                .solderTo(buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendStateHashedNotification));

        state.stateSignaturesOutputWire()
                .solderTo(
                        "ExecutionSignatureSubmission",
                        "state signatures",
                        inputs.executionLayer()::submitStateSignature);

        state.oldestMinimumBirthRoundOnDiskOutputWire()
                .solderTo(buildingBlocks.pcesModule().minimumBirthRoundInputWire(), INJECT);

        final OutputWire<StateSavingResult> stateSavingResultOutputWire = state.stateSavingResultOutputWire();
        stateSavingResultOutputWire.solderTo(
                buildingBlocks.statusMonitorModule().stateWrittenToDiskInputWire());
        stateSavingResultOutputWire
                .buildTransformer(
                        "stateSavedNotifier",
                        "state saved results",
                        result -> new StateWriteToDiskCompleteNotification(
                                result.round(), result.consensusTimestamp(), result.freezeState()))
                .solderTo(
                        buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendStateWrittenToDiskNotification),
                        INJECT);
    }

    /**
     * Solder the ISS detection module's outputs to their consumers.
     */
    private static void wireIssDetectionOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<IssNotification> issNotification =
                buildingBlocks.issDetectionModule().issNotificationOutputWire();

        issNotification.solderTo(buildingBlocks.statusMonitorModule().issNotificationInputWire());
        issNotification.solderTo(buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendIssNotification));
    }

    /**
     * Solder the running event hash override outputs to their consumers.
     */
    private static void wireRunningHashOverrideOutputs(@NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<RunningEventHashOverride> runningHashUpdate =
                buildingBlocks.runningEventHashOverrideWiring().runningHashUpdateOutput();

        runningHashUpdate.solderTo(buildingBlocks.transactionHandlingModule().hashOverrideInputWire());
        runningHashUpdate.solderTo(
                buildingBlocks.consensusEventStreamWiring().getInputWire(ConsensusEventStream::legacyHashOverride));
    }

    /**
     * Solder the platform monitor's status output to all components that need it.
     */
    private static void wirePlatformMonitorOutputs(
            @NonNull final ConsensusLayerInputs inputs, @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<PlatformStatus> platformStatus =
                buildingBlocks.statusMonitorModule().platformStatusOutputWire();

        platformStatus.solderTo(buildingBlocks.eventCreatorModule().platformStatusInputWire());
        platformStatus.solderTo(buildingBlocks.hashgraphModule().platformStatusInputWire(), INJECT);
        platformStatus.solderTo("ExecutionStatusHandler", "status updates", inputs.executionLayer()::newPlatformStatus);
        platformStatus.solderTo(buildingBlocks.gossipModule().platformStatusInputWire(), INJECT);
        platformStatus.solderTo(buildingBlocks.stateModule().platformStatusInputWire(), INJECT);
        platformStatus.solderTo(
                buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendPlatformStatusChangeNotification));
    }

    /**
     * Solder framework infrastructure wires (the health monitor and the heartbeat) to their consumers.
     */
    private static void wireInfrastructure(
            @NonNull final ConsensusLayerInputs inputs, @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<Duration> healthMonitor = buildingBlocks.wiringModel().getHealthMonitorWire();
        healthMonitor.solderTo(buildingBlocks.eventCreatorModule().healthStatusInputWire());
        healthMonitor.solderTo(buildingBlocks.gossipModule().healthStatusInputWire());
        healthMonitor.solderTo(
                "executionHealthInput", "healthyDuration", inputs.executionLayer()::reportUnhealthyDuration);
    }
}
