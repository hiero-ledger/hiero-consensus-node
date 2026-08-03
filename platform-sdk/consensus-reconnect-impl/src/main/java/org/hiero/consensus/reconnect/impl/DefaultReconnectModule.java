// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.reconnect.ReconnectModule;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.concurrent.framework.config.CompositeThreadNamingConfiguration;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.model.node.NodeId;

/**
 * The default implementation of {@link ReconnectModule}.
 */
public class DefaultReconnectModule implements ReconnectModule {

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final Roster currentRoster,
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks,
            @NonNull final Platform platform,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final NodeId selfId) {
        final ReconnectCoordinator reconnectCoordinator = new ReconnectCoordinator(buildingBlocks);

        final ReconnectController reconnectController = new ReconnectController(
                configuration,
                time,
                currentRoster,
                platform,
                reconnectCoordinator,
                stateLifecycleManager,
                buildingBlocks.savedStateController(),
                consensusStateEventHandler,
                buildingBlocks.reservedSignedStateResultPromise(),
                selfId,
                buildingBlocks.fallenBehindMonitor(),
                new DefaultSignedStateValidator());

        final Thread reconnectControllerThread = new ThreadConfiguration(AdHocThreadManager.getStaticThreadManager())
                .setThreadNameProvider(
                        CompositeThreadNamingConfiguration.create("platform-core", "reconnectController"))
                .setRunnable(reconnectController)
                .build(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            reconnectController.stopReconnectLoop();
            reconnectControllerThread.interrupt();
        }));
    }
}
