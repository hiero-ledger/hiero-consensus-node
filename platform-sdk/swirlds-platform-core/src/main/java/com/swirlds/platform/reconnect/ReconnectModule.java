// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.model.node.NodeId;

/**
 * The {@code ReconnectModule} contains the logic for handling reconnects. It is responsible for managing the
 * lifecycle of the reconnect process, including initializing the necessary components, handling the reconnect process,
 * and cleaning up after the reconnect is complete.
 */
public interface ReconnectModule {

    /**
     * Initializes the module.
     *
     * @param configuration the configuration for this module
     * @param time the time source
     * @param currentRoster the current roster of the network
     * @param buildingBlocks the building blocks for the consensus layer
     * @param platform the platform to use for performing platform operations
     * @param stateLifecycleManager the manager for the lifecycle of the platform state
     * @param consensusStateEventHandler the handler for consensus state events
     * @param selfId the ID of this node
     */
    void initialize(
            @NonNull Configuration configuration,
            @NonNull Time time,
            @NonNull Roster currentRoster,
            @NonNull ConsensusLayerBuildingBlocks buildingBlocks,
            @NonNull Platform platform,
            @NonNull StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull NodeId selfId);
}
