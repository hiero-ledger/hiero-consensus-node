// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.Gossip;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.wiring.framework.model.DeterministicWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.NoInput;
import org.hiero.consensus.wiring.framework.wires.output.StandardOutputWire;
import org.hiero.otter.fixtures.network.simulation.EventReceiver;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;

/**
 * Simulates the {@link Gossip} subsystem for a group of nodes running on a {@link SimulatedNetworkConnectivity}.
 */
public class SimulatedGossip implements Gossip, EventReceiver {

    private final SimulatedNetworkConnectivity networkConnectivity;
    private final NodeId selfId;
    private IntakeEventCounter intakeEventCounter;

    private StandardOutputWire<PlatformEvent> eventOutput;

    /** The wiring model used for this node. Used to determine if the node is running or halted. */
    private DeterministicWiringModel deterministicWiringModel;

    /**
     * Constructor.
     *
     * @param networkConnectivity the network connections on which this gossip system will run
     * @param selfId the ID of the node running this gossip system
     */
    public SimulatedGossip(
            @NonNull final SimulatedNetworkConnectivity networkConnectivity, @NonNull final NodeId selfId) {
        this.networkConnectivity = requireNonNull(networkConnectivity);
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
            @NonNull final BindableInputWire<NoInput, Void> pauseInput,
            @NonNull final BindableInputWire<NoInput, Void> resumeInput,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput,
            @NonNull final StandardOutputWire<SyncProgress> syncLagOutput) {

        this.eventOutput = requireNonNull(eventOutput);
        this.deterministicWiringModel = (DeterministicWiringModel) requireNonNull(model);
        eventInput.bindConsumer(event -> {
            // Self-created events have no sender until now; the network identifies the source by this field
            event.setSenderId(selfId);
            networkConnectivity.submitEvent(event);
        });
        eventWindowInput.bindConsumer(eventWindow -> networkConnectivity.updateEventWindow(selfId, eventWindow));

        startInput.bindConsumer(ignored -> {});
        stopInput.bindConsumer(ignored -> {});
        clearInput.bindConsumer(ignored -> {});
        pauseInput.bindConsumer(ignored -> {});
        resumeInput.bindConsumer(ignored -> {});
        systemHealthInput.bindConsumer(ignored -> {});
        platformStatusInput.bindConsumer(ignored -> {});
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receiveEvent(@NonNull final PlatformEvent event) {
        if (deterministicWiringModel.isRunning()) {
            forwardEvent(event);
            return true;
        }
        return false;
    }

    private void forwardEvent(@NonNull final PlatformEvent event) {
        if (intakeEventCounter != null) {
            intakeEventCounter.eventEnteredIntakePipeline(event.getSenderId());
        }
        eventOutput.forward(event);
    }

    /**
     * Resets this node's gossip point so that it will receive all necessary events after a restart.
     */
    public void onRestart() {
        networkConnectivity.resetCursor(selfId);
    }
}
