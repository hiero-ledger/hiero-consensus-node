// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static java.util.Objects.requireNonNull;

import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Simulates the {@link Gossip} subsystem for a group of nodes running on a {@link SimulatedNetwork}.
 */
public class SimulatedGossip implements Gossip {

    private final SimulatedNetwork network;
    private final NodeId selfId;
    private IntakeEventCounter intakeEventCounter;

    private StandardOutputWire<PlatformEvent> eventOutput;

    /** The wiring model used for this node. Used to determine if the node is running or halted. */
    private DeterministicWiringModel deterministicWiringModel;

    /**
     * A buffer of all events this gossip instance has received. This is used to re-deliver events that were received
     * while the node was halted.
     */
    private final List<PlatformEvent> eventBuffer = new ArrayList<>();

    /** Keeps track of the status of the node the last time we checked if it was running */
    private boolean wasPreviouslyHalted = false;

    /**
     * Constructor.
     *
     * @param network the network on which this gossip system will run
     * @param selfId the ID of the node running this gossip system
     */
    public SimulatedGossip(@NonNull final SimulatedNetwork network, @NonNull final NodeId selfId) {
        this.network = requireNonNull(network);
        this.selfId = requireNonNull(selfId);
    }

    /**
     * Add an intake event counter that gets incremented for all events that enter the intake pipeline.
     *
     * @param intakeEventCounter the intake event counter
     */
    public void provideIntakeEventCounter(@NonNull final IntakeEventCounter intakeEventCounter) {
        this.intakeEventCounter = requireNonNull(intakeEventCounter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(
            @NonNull final WiringModel model,
            @NonNull final BindableInputWire<PlatformEvent, Void> eventInput,
            @NonNull final BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull final StandardOutputWire<PlatformEvent> eventOutput,
            @NonNull final BindableInputWire<NoInput, Void> startInput,
            @NonNull final BindableInputWire<NoInput, Void> stopInput,
            @NonNull final BindableInputWire<NoInput, Void> clearInput,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput,
            @NonNull final StandardOutputWire<Double> syncLagOutput) {

        this.eventOutput = requireNonNull(eventOutput);
        this.deterministicWiringModel = (DeterministicWiringModel) requireNonNull(model);
        eventInput.bindConsumer(event -> network.submitEvent(selfId, event));

        eventWindowInput.bindConsumer(ignored -> {});
        startInput.bindConsumer(ignored -> {});
        stopInput.bindConsumer(ignored -> {});
        clearInput.bindConsumer(ignored -> {});
        systemHealthInput.bindConsumer(ignored -> {});
        platformStatusInput.bindConsumer(ignored -> {});
    }

    /**
     * This method is called every time this node receives an event from the network.
     *
     * @param event the event that was received
     */
    void receiveEvent(@NonNull final PlatformEvent event) {
        if (deterministicWiringModel.isRunning()) {
            if (wasPreviouslyHalted) {
                eventBuffer.forEach(this::forwardEvent);
                wasPreviouslyHalted = false;
            }
            forwardEvent(event);
        } else {
            wasPreviouslyHalted = true;
        }
        eventBuffer.add(event);
    }

    private void forwardEvent(@NonNull final PlatformEvent event) {
        if (intakeEventCounter != null) {
            intakeEventCounter.eventEnteredIntakePipeline(event.getSenderId());
        }
        eventOutput.forward(event);
    }
}
