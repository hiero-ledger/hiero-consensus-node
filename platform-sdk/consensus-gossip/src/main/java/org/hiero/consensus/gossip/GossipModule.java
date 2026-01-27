// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Gossip module interface.
 */
public interface GossipModule {

    /**
     * Initialize the gossip module.
     */
    void initialize();

    /**
     * {@link OutputWire} for events received through gossip.
     *
     * @return the {@link OutputWire} for the events
     */
    @NonNull
    OutputWire<PlatformEvent> receivedEventOutputWire();

    /**
     * {@link OutputWire} for the average sync lag.
     *
     * @return the {@link OutputWire} for the sync progress
     */
    @NonNull
    OutputWire<SyncProgress> syncProgressOutputWire();

    /**
     * {@link InputWire} for events to be gossiped.
     *
     * @return the {@link InputWire} for events to be gossiped
     */
    @InputWireLabel("events to gossip")
    @NonNull
    InputWire<PlatformEvent> eventToGossipInputWire();

    /**
     * {@link InputWire} for the event window received from the {@code Hashgraph} module.
     *
     * @return the {@link InputWire} for the event window
     */
    @InputWireLabel("event window")
    @NonNull
    InputWire<EventWindow> eventWindowInputWire();

    /**
     * {@link InputWire} for the platform status received from the {@code StatusStateMachine}.
     *
     * @return the {@link InputWire} for the platform status
     */
    @InputWireLabel("PlatformStatus")
    @NonNull
    InputWire<PlatformStatus> platformStatusInputWire();

    /**
     * {@link InputWire} for the health status of the consensus module received from the
     * {@code HealthMonitor}. The health status is represented as a {@link Duration} indicating the
     * time since the system became unhealthy.
     *
     * @return the {@link InputWire} for the health status
     */
    @InputWireLabel("health info")
    @NonNull
    InputWire<Duration> healthStatusInputWire();

    /**
     * {@link InputWire} for control signals to start gossiping.
     *
     * @return the {@link InputWire} for start signals
     */
    @InputWireLabel("start")
    @NonNull
    InputWire<NoInput> startInputWire();

    /**
     * {@link InputWire} for control signals to stop gossiping.
     *
     * @return the {@link InputWire} for stop signals
     */
    @InputWireLabel("stop")
    @NonNull
    InputWire<NoInput> stopInputWire();

    /**
     * {@link InputWire} for control signals to clear internal gossip state.
     *
     * @return the {@link InputWire} for clear signals
     */
    @InputWireLabel("clear")
    @NonNull
    InputWire<NoInput> clearInputWire();

    /**
     * {@link InputWire} for control signals to pause gossiping.
     *
     * @return the {@link InputWire} for pause signals
     */
    @InputWireLabel("pause")
    @NonNull
    InputWire<NoInput> pauseInputWire();

    /**
     * {@link InputWire} for control signals to resume gossiping.
     *
     * @return the {@link InputWire} for resume signals
     */
    @InputWireLabel("resume")
    @NonNull
    InputWire<NoInput> resumeInputWire();

    /**
     * Flushes the gossip module.
     */
    void flush();
}
