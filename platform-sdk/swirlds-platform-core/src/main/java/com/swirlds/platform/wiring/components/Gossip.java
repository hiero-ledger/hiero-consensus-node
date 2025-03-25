// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring.components;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.wiring.NoInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implements gossip with network peers.
 */
public interface Gossip {

    /**
     * Bind the input wires to the gossip implementation.
     *
     * @param model               the wiring model for this node
     * @param eventInput          the input wire for events, events sent here should be gossiped to the network
     * @param eventWindowInput    the input wire for the current event window
     * @param eventOutput         the output wire for events received from peers during gossip
     * @param startInput          used to tell gossip to start
     * @param stopInput           used to tell gossip to stop
     * @param clearInput          used to tell gossip to clear its internal state
     * @param systemHealthInput   used to tell gossip the health of the system, carries the duration that the system has
     *                            been in an unhealthy state
     * @param platformStatusInput used to tell gossip the status of the platform
     */
    void bind(
            @NonNull WiringModel model,
            @NonNull BindableInputWire<PlatformEvent, Void> eventInput,
            @NonNull BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull StandardOutputWire<PlatformEvent> eventOutput,
            @NonNull BindableInputWire<NoInput, Void> startInput,
            @NonNull BindableInputWire<NoInput, Void> stopInput,
            @NonNull BindableInputWire<NoInput, Void> clearInput,
            @NonNull BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull BindableInputWire<PlatformStatus, Void> platformStatusInput);
}
