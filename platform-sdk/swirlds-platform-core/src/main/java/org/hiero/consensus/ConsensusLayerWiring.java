// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static com.swirlds.component.framework.wires.SolderType.INJECT;

import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
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
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Encapsulates wiring for {@link ConsensusLayer}.
 *
 * <p>{@link #wire(ExecutionLayerCallbacks, ConsensusLayerBuildingBlocks)} reads as a table of contents: one
 * {@code wireXxxOutputs} method per producing component, each soldering that component's output wires to every
 * consumer. To find where a component's output goes, open the method named after it.
 * <p>Methods taking ConsensusLayerInputs are the ones that wire into the execution layer / external consumers.
 */
public class ConsensusLayerWiring {

    private ConsensusLayerWiring() {}

    /**
     * Wire the components together.
     *
     * @param executionLayerCallbacks callbacks to the execution layer
     * @param buildingBlocks the building blocks of the consensus layer
     */
    public static void wire(
            @NonNull final ExecutionLayerCallbacks executionLayerCallbacks,
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        Objects.requireNonNull(executionLayerCallbacks);
        Objects.requireNonNull(buildingBlocks);

        wireGossipOutputs(buildingBlocks);
        wireEventIntakeOutputs(buildingBlocks);
        wireEventCreatorOutputs(buildingBlocks);
        wirePcesOutputs(buildingBlocks);
        wireHashgraphOutputs(executionLayerCallbacks, buildingBlocks);
        wireInitialEventWindowDispatcher(buildingBlocks);
        wirePlatformMonitorOutputs(executionLayerCallbacks, buildingBlocks);
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
            @NonNull final ExecutionLayerCallbacks executionLayerCallbacks,
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final HashgraphModule hashgraph = buildingBlocks.hashgraphModule();

        final OutputWire<PlatformEvent> staleEvent = hashgraph.staleEventOutputWire();
        staleEvent.solderTo("staleEventCallback", "stale events", executionLayerCallbacks::onStaleEvent);

        final OutputWire<PlatformEvent> preconsensusEventOutputWire = hashgraph.preconsensusEventOutputWire();

        // pre-handle gets pre-consensus events from the consensus engine
        // the consensus engine ensures that all pre-consensus events either reach consensus of become stale
        preconsensusEventOutputWire.solderTo("preHandleCallback", "pre-consensus events", executionLayerCallbacks::onPreHandle);

        final OutputWire<ConsensusRound> consensusRoundOutputWire = hashgraph.consensusRoundOutputWire();

        consensusRoundOutputWire.solderTo(buildingBlocks.eventIntakeModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.gossipModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.pcesModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo(buildingBlocks.eventCreatorModule().consensusRoundInputWire(), INJECT);
        consensusRoundOutputWire.solderTo("consensusRound", "consensusRound", executionLayerCallbacks::onRound);

        consensusRoundOutputWire
                .buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)
                .solderTo(buildingBlocks.consensusEventStreamWiring().getInputWire(ConsensusEventStream::addEvents));

        consensusRoundOutputWire.solderTo(buildingBlocks.statusMonitorModule().consensusRoundInputWire());
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private static void wireInitialEventWindowDispatcher(
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<EventWindow> eventWindowOutputWire =
                buildingBlocks.initialEventWindowDispatcher().getOutputWire();

        eventWindowOutputWire.solderTo(buildingBlocks.eventIntakeModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.gossipModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.pcesModule().initialEventWindowInputWire(), INJECT);
        eventWindowOutputWire.solderTo(buildingBlocks.eventCreatorModule().initialEventWindowInputWire(), INJECT);
    }

    /**
     * Solder the platform monitor's status output to all components that need it.
     */
    private static void wirePlatformMonitorOutputs(
            @NonNull final ExecutionLayerCallbacks executionLayerCallbacks,
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        final OutputWire<PlatformStatus> platformStatus =
                buildingBlocks.statusMonitorModule().platformStatusOutputWire();

        platformStatus.solderTo(buildingBlocks.eventCreatorModule().platformStatusInputWire());
        platformStatus.solderTo(buildingBlocks.hashgraphModule().platformStatusInputWire(), INJECT);
        platformStatus.solderTo(buildingBlocks.gossipModule().platformStatusInputWire(), INJECT);
        platformStatus.solderTo("ExecutionStatusHandler", "status updates", executionLayerCallbacks::onPlatformStatusChange);
    }
}
