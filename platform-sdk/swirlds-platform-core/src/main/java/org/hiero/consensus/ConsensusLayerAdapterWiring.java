// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static com.swirlds.component.framework.wires.SolderType.INJECT;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.ConsensusLayer.StatusUpdate;
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

/**
 * Encapsulates wiring for {@link com.swirlds.platform.SwirldsPlatform}.
 *
 * <p>{@link #wire(ConsensusLayerAdapterInputs, ConsensusLayerAdapterBuildingBlocks)} reads as a table of contents: one
 * {@code wireXxxOutputs} method per producing component, each soldering that component's output wires to every
 * consumer. To find where a component's output goes, open the method named after it.
 * <p>Methods taking ConsensusLayerInputs are the ones that wire into the execution layer / external consumers.
 */
public class ConsensusLayerAdapterWiring {

    private ConsensusLayerAdapterWiring() {}

    /**
     * Wire the components together.
     *
     * @param inputs         the inputs to the consensus layer
     * @param buildingBlocks the building blocks of the consensus layer
     */
    public static void wire(
            @NonNull final ConsensusLayerAdapterInputs inputs,
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(buildingBlocks);

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
     * Solder the transaction handling module's outputs to their consumers.
     */
    private static void wireTransactionHandlingOutputs(
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
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
            @NonNull final ConsensusLayerAdapterInputs inputs,
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
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

        state.stateSavingResultOutputWire().buildTransformer("oldestSnapshotTransformer", "savedStateResult",
                        StateSavingResult::oldestRestartableConsensusSnapshot)
                .solderTo("consensusLayer", "oldestRestartableSnapshot", buildingBlocks.consensusLayer()::oldestRestartableSnapshot);

        final OutputWire<StateSavingResult> stateSavingResultOutputWire = state.stateSavingResultOutputWire();
        stateSavingResultOutputWire.solderTo("consensusLayer", "onFreezeCompleteStatusUpdate",
                (_) -> buildingBlocks.consensusLayer().onStatusUpdate(StatusUpdate.FREEZE_COMPLETE));
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
    private static void wireIssDetectionOutputs(@NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        final OutputWire<IssNotification> issNotification =
                buildingBlocks.issDetectionModule().issNotificationOutputWire();

        issNotification.solderTo("consensusLayer", "onIssStatusUpdate",
                (_) -> buildingBlocks.consensusLayer().onStatusUpdate(StatusUpdate.CATASTROPHIC_FAILURE));
        issNotification.solderTo(buildingBlocks.notifierWiring().getInputWire(AppNotifier::sendIssNotification));
    }

    /**
     * Solder the running event hash override outputs to their consumers.
     */
    private static void wireRunningHashOverrideOutputs(
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        final OutputWire<RunningEventHashOverride> runningHashUpdate =
                buildingBlocks.runningEventHashOverrideWiring().runningHashUpdateOutput();

        runningHashUpdate.solderTo(buildingBlocks.transactionHandlingModule().hashOverrideInputWire());
    }

    /**
     * Solder framework infrastructure wires (the health monitor and the heartbeat) to their consumers.
     */
    private static void wireInfrastructure(
            @NonNull final ConsensusLayerAdapterInputs inputs,
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks) {
        final OutputWire<Duration> healthMonitor = buildingBlocks.wiringModel().getHealthMonitorWire();
        healthMonitor.solderTo(buildingBlocks.eventCreatorModule().healthStatusInputWire());
        healthMonitor.solderTo(buildingBlocks.gossipModule().healthStatusInputWire());
        healthMonitor.solderTo(
                "executionHealthInput", "healthyDuration", inputs.executionLayer()::reportUnhealthyDuration);
    }
}
