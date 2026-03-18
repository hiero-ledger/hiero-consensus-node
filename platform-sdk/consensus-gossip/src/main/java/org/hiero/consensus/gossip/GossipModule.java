// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Gossip module interface.
 */
public interface GossipModule {

    /**
     * Initialize the gossip module.
     *
     * @param model the wiring model to use for connecting wires
     * @param configuration the configuration for the gossip module
     * @param metrics the metrics system
     * @param time the time source
     * @param keysAndCerts the keys and certificates of this node
     * @param currentRoster the current roster of nodes in the network
     * @param selfId the ID of this node
     * @param appVersion the application version
     * @param intakeEventCounter the counter for events in the intake pipeline
     * @param latestCompleteState a supplier for the latest complete signed state
     * @param reservedSignedStateResultPromise a promise for the result of reserving a signed state
     * @param fallenBehindMonitor the monitor for detecting if the node has fallen behind
     * @param stateLifecycleManager the manager for the lifecycle of the platform state
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull KeysAndCerts keysAndCerts,
            @NonNull Roster currentRoster,
            @NonNull NodeId selfId,
            @NonNull SemanticVersion appVersion,
            @NonNull IntakeEventCounter intakeEventCounter,
            @NonNull Supplier<ReservedSignedState> latestCompleteState,
            @NonNull BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull FallenBehindMonitor fallenBehindMonitor,
            @NonNull StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager);

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
