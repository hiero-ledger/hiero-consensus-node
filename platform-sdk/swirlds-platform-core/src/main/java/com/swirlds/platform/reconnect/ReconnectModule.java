// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.consensus.ConsensusLayer;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.reconnect.PeerProtocolFactory;
import org.hiero.consensus.state.signed.ReservedSignedState;

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
     * @param consensusLayer the consensus layer to use for performing consensus operations
     * @param stateLifecycleManager the manager for the lifecycle of the platform state
     * @param consensusStateEventHandler the handler for consensus state events
     * @param selfId the ID of this node
     */
    void initialize(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final Roster currentRoster,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks,
            @NonNull final Platform platform,
            @NonNull final ConsensusLayer consensusLayer,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final NodeId selfId);

    /**
     * Provides an implementation of {@link PeerProtocolFactory} that implements the execution layer's part
     * of the reconnect protocol. This is a temporary method which will be deleted when reconnect teaching moves
     * to the block node.
     *
     * @return the peer protocol factory
     */
    PeerProtocolFactory getReconnectPeerProtocolFactory();
}
