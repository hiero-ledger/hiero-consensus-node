// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.gossip.config.GossipWiringConfig;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Wiring for gossip.
 */
public class GossipWiring {

    /**
     * The wiring model for this node.
     */
    private final WiringModel model;

    /**
     * The task scheduler for the gossip component.
     */
    private final TaskScheduler<Void> scheduler;

    /**
     * Events to be gossiped are sent here.
     */
    private final BindableInputWire<PlatformEvent, Void> eventInput;

    /**
     * Event window updates are sent here.
     */
    private final BindableInputWire<EventWindow, Void> eventWindowInput;

    /**
     * Events received through gossip are sent out over this wire.
     */
    private final StandardOutputWire<PlatformEvent> eventOutput;

    /**
     * Average sync lag is reported over this wire
     */
    private final StandardOutputWire<SyncProgress> syncProgressOutput;

    /**
     * This wire is used to start gossip.
     */
    private final BindableInputWire<NoInput, Void> startInput;

    /**
     * This wire is used to stop gossip.
     */
    private final BindableInputWire<NoInput, Void> stopInput;

    /**
     * This wire is used to clear internal gossip state.
     */
    private final BindableInputWire<NoInput, Void> clearInput;

    /**
     * This wire is used to tell gossip the health of the system, carries the duration that the system has been in an
     * unhealthy state.
     */
    private final BindableInputWire<Duration, Void> systemHealthInput;

    /**
     * This wire is used to tell gossip the status of the platform.
     */
    private final BindableInputWire<PlatformStatus, Void> platformStatusInput;
    /**
     * This wire is used to pause gossip.
     */
    private final BindableInputWire<NoInput, Void> pauseInput;
    /**
     * This wire is used to resume gossip.
     */
    private final BindableInputWire<NoInput, Void> resumeInput;

    public GossipWiring(@NonNull final Configuration configuration, @NonNull final WiringModel model) {
        this.model = model;

        scheduler = model.<Void>schedulerBuilder("gossip")
                .configure(configuration.getConfigData(GossipWiringConfig.class).gossip())
                .build();

        eventInput = scheduler.buildInputWire("events to gossip");
        eventWindowInput = scheduler.buildInputWire("event window");
        eventOutput = scheduler.buildSecondaryOutputWire();
        syncProgressOutput = scheduler.buildSecondaryOutputWire();

        startInput = scheduler.buildInputWire("start");
        stopInput = scheduler.buildInputWire("stop");
        clearInput = scheduler.buildInputWire("clear");
        systemHealthInput = scheduler.buildInputWire("health info");
        platformStatusInput = scheduler.buildInputWire("PlatformStatus");
        pauseInput = scheduler.buildInputWire("pause");
        resumeInput = scheduler.buildInputWire("resume");
    }

    /**
     * Bind the wiring to a gossip implementation.
     *
     * @param gossip the gossip implementation
     */
    public void bind(@NonNull final Gossip gossip) {
        gossip.bind(
                model,
                eventInput,
                eventWindowInput,
                eventOutput,
                startInput,
                stopInput,
                clearInput,
                pauseInput,
                resumeInput,
                systemHealthInput,
                platformStatusInput,
                syncProgressOutput);
    }

    /**
     * Get the input wire for events to be gossiped to the network.
     *
     * @return the input wire for events
     */
    @NonNull
    public InputWire<PlatformEvent> getEventInput() {
        return eventInput;
    }

    /**
     * Get the input wire for the current event window.
     *
     * @return the input wire for the event window
     */
    @NonNull
    public InputWire<EventWindow> getEventWindowInput() {
        return eventWindowInput;
    }

    /**
     * Get the output wire for events received from peers during gossip.
     *
     * @return the output wire for events
     */
    @NonNull
    public OutputWire<PlatformEvent> getEventOutput() {
        return eventOutput;
    }

    /**
     * Get the output wire for average sync lag during gossip
     *
     * @return the output wire for sync lag
     */
    public OutputWire<SyncProgress> getSyncProgressOutput() {
        return syncProgressOutput;
    }

    /**
     * Get the input wire to start gossip.
     *
     * @return the input wire to start gossip
     */
    @NonNull
    public InputWire<NoInput> getStartInput() {
        return startInput;
    }

    /**
     * Get the input wire to stop gossip.
     *
     * @return the input wire to stop gossip
     */
    @NonNull
    public InputWire<NoInput> getStopInput() {
        return stopInput;
    }

    /**
     * Get the input wire to pause gossip.
     *
     * @return the input wire to pause gossip
     */
    @NonNull
    public InputWire<NoInput> pauseInput() {
        return pauseInput;
    }

    /**
     * Get the input wire to resume gossip.
     *
     * @return the input wire to resume gossip
     */
    @NonNull
    public InputWire<NoInput> resumeInput() {
        return resumeInput;
    }

    /**
     * Get the input wire to clear the gossip state.
     *
     * @return the input wire to clear the gossip state
     */
    @NonNull
    public InputWire<NoInput> getClearInput() {
        return clearInput;
    }

    /**
     * Get the input wire to tell gossip the health of the system.
     *
     * @return the input wire to tell gossip the health of the system
     */
    @NonNull
    public InputWire<Duration> getSystemHealthInput() {
        return systemHealthInput;
    }

    /**
     * Get the input wire to tell gossip the status of the platform.
     *
     * @return the input wire to tell gossip the status of the platform
     */
    @NonNull
    public InputWire<PlatformStatus> getPlatformStatusInput() {
        return platformStatusInput;
    }

    /**
     * Flush the gossip scheduler.
     */
    public void flush() {
        scheduler.flush();
    }
}
