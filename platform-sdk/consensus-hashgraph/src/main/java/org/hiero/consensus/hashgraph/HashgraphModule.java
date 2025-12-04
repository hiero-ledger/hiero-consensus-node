// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.freeze.FreezeCheckHolder;
import org.hiero.consensus.model.event.PlatformEvent;
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
     * @param configuration the configuration
     * @param metrics the metrics registry
     * @param time the time source
     * @param roster the active roster
     * @param selfId this node's ID
     * @param freezeChecker the freeze checker used to determine when a freeze is in progress
     */
    void initialize(
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull Roster roster,
            @NonNull NodeId selfId,
            @NonNull FreezeCheckHolder freezeChecker);

    /**
     * The primary input wire of the Hashgraph module. This input wire accepts events to be added to the consensus
     * algorithm. Events must be provided in a valid topological order. When enough events are added via this input
     * wire, one or more outputs will be generated on the {@link #consensusEngineOutputWire()}.
     *
     * @return the event input wire
     * @see #consensusEngineOutputWire()
     */
    InputWire<PlatformEvent> eventInputWire();

    /**
     * The primary output wire of the Hashgraph module. This output wire produces consensus engine output.
     *
     * @return the consensus engine output wire
     * @see #eventInputWire()
     */
    OutputWire<ConsensusEngineOutput> consensusEngineOutputWire();

    /**
     * Informs the module about platform statu updates.
     *
     * @return the platform status input wire
     */
    InputWire<PlatformStatus> platformStatusInputWire();

    /**
     * Updates the internal state of the module to align with the given consensus snapshot. This happens at
     * restart/reconnect boundaries.
     *
     * @return the consensus snapshot input wire
     */
    InputWire<ConsensusSnapshot> consensusSnapshotInputWire();
}
