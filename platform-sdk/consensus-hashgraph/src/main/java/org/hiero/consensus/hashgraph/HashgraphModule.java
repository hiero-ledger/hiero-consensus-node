// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
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
            @NonNull FreezePeriodChecker freezeChecker);

    /**
     * The primary input wire of the Hashgraph module. This input wire accepts events to be added to the consensus
     * algorithm. Events must be provided in a valid topological order. When enough events are added via this input
     * wire, output will be generated on the {@link #consensusRoundsOutputWire()}.
     *
     * @return the event input wire
     * @see #consensusRoundsOutputWire()
     * @see #preconsensusEventsOutputWire()
     * @see #staleEventsOutputWire()
     */
    InputWire<PlatformEvent> eventInputWire();

    /**
     * The primary output wire of the Hashgraph module. This output wire produces consensus engine output.
     *
     * @return the consensus engine output wire
     * @see #eventInputWire()
     */
    OutputWire<List<ConsensusRound>> consensusRoundsOutputWire();

    /**
     * An output wire that forwards pre-consensus events that are still waiting to reach consensus when consensus has advanced.
     *
     * @return the pre-consensus events output wire
     */
    OutputWire<List<PlatformEvent>> preconsensusEventsOutputWire();

    /**
     * An output wire that forwards events that became stale as a result of consensus advancing.
     *
     * @return the stale events output wire
     */
    OutputWire<List<PlatformEvent>> staleEventsOutputWire();

    /**
     * Informs the module about platform status updates.
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
