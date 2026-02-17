// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

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
     * @param components the platform components to use
     * @param platform the platform to use for performing platform operations
     * @param platformCoordinator the coordinator for complex tasks
     * @param stateLifecycleManager the manager for the lifecycle of the platform state
     * @param savedStateController the controller for managing saved states
     * @param consensusStateEventHandler the handler for consensus state events
     * @param reservedSignedStateResultPromise a provider for reserved signed state results
     * @param selfId the ID of this node
     * @param fallenBehindMonitor the monitor for detecting if this node has fallen behind
     */
    void initialize(
            @NonNull Configuration configuration,
            @NonNull Time time,
            @NonNull Roster currentRoster,
            @NonNull PlatformComponents components,
            @NonNull Platform platform,
            @NonNull PlatformCoordinator platformCoordinator,
            @NonNull StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull SavedStateController savedStateController,
            @NonNull ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull NodeId selfId,
            @NonNull FallenBehindMonitor fallenBehindMonitor);
}
