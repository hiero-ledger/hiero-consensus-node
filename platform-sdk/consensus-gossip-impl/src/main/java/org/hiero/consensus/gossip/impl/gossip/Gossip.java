// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
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
     * @param startInput          an input wire that will be bound to gossip's start() method which involves starting network threads and all gossiping activity
     * @param stopInput           an input wire that will be bound to gossip's stop() method which involves stoping network threads and all gossip activity
     * @param clearInput          an input wire that will be bound to gossip's clear() method used for the internal state of the gossip engine.
     * @param pauseInput          an input wire that will be bound to gossip's pause() method which involves pausing gossiping activity
     * @param resumeInput         an input wire that will be bound to gossip's resume() method which will resume gossiping activity.
     *                           Should be called exactly once after each call to pause()
     * @param systemHealthInput   used to tell gossip the health of the system, carries the duration that the system has
     *                            been in an unhealthy state
     * @param platformStatusInput used to tell gossip the status of the platform
     * @param syncProgressOutput  used to report current sync status against specific peer
     */
    void bind(
            @NonNull WiringModel model,
            @NonNull BindableInputWire<PlatformEvent, Void> eventInput,
            @NonNull BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull StandardOutputWire<PlatformEvent> eventOutput,
            @NonNull BindableInputWire<NoInput, Void> startInput,
            @NonNull BindableInputWire<NoInput, Void> stopInput,
            @NonNull BindableInputWire<NoInput, Void> clearInput,
            @NonNull BindableInputWire<NoInput, Void> pauseInput,
            @NonNull BindableInputWire<NoInput, Void> resumeInput,
            @NonNull BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull BindableInputWire<PlatformStatus, Void> platformStatusInput,
            @NonNull StandardOutputWire<SyncProgress> syncProgressOutput);
}
