// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * The Hashgraph Module is responsible for ordering events deterministically into consensus order and assigning each
 * event and contained transaction a consensus timestamp. Consensus ordered events are returned in groups called
 * rounds.
 */
public interface HashgraphModule {

    /**
     * Initialize the Hashgraph module.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @param metrics the metrics registry
     * @param time the time source
     * @param roster the active roster
     * @param selfId this node's ID
     * @param freezeChecker the freeze checker used to determine when a freeze is in progress
     */
    void initialize(
            @NonNull WiringModel model,
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull Roster roster,
            @NonNull NodeId selfId,
            @NonNull FreezePeriodChecker freezeChecker,
            @Nullable EventPipelineTracker eventPipelineTracker);

    /**
     * The primary input wire of the Hashgraph module. This input wire accepts events to be added to the consensus
     * algorithm. Events must be provided in a valid topological order. When enough events are added via this input
     * wire, output will be generated on the {@link #consensusRoundOutputWire()}.
     *
     * @return the event input wire
     * @see #consensusRoundOutputWire()
     * @see #preconsensusEventOutputWire()
     * @see #staleEventOutputWire()
     */
    @InputWireLabel("persisted ordered events")
    @NonNull
    InputWire<PlatformEvent> eventInputWire();

    /**
     * The primary output wire of the Hashgraph module. This output wire produces consensus engine output.
     *
     * @return the consensus engine output wire
     * @see #eventInputWire()
     */
    @NonNull
    OutputWire<ConsensusRound> consensusRoundOutputWire();

    /**
     * An output wire that forwards pre-consensus events that are still waiting to reach consensus when consensus has advanced.
     *
     * @return the pre-consensus events output wire
     */
    @NonNull
    OutputWire<PlatformEvent> preconsensusEventOutputWire();

    /**
     * An output wire that forwards events that became stale as a result of consensus advancing.
     *
     * @return the stale events output wire
     */
    @NonNull
    OutputWire<PlatformEvent> staleEventOutputWire();

    /**
     * Informs the module about platform status updates.
     *
     * @return the platform status input wire
     */
    @InputWireLabel("platform status")
    @NonNull
    InputWire<PlatformStatus> platformStatusInputWire();

    /**
     * Updates the internal state of the module to align with the given consensus snapshot. This happens at
     * restart/reconnect boundaries.
     *
     * @return the consensus snapshot input wire
     */
    @InputWireLabel("consensus snapshot")
    @NonNull
    InputWire<ConsensusSnapshot> consensusSnapshotInputWire();

    /**
     * Begin squelching input. While squelching is active, no new tasks will be added on any input wires.
     * This is useful during reconnects to flush the system of events.
     */
    void startSquelching();

    /**
     * Stop squelching input. New tasks will once again be added on input wires.
     */
    void stopSquelching();

    /**
     * Flushes all tasks currently enqueue in input wires in the order of data flow.
     */
    void flush();
}
