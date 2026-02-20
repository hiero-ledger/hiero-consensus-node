// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.reconnect.ReconnectModule;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

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
            @NonNull final PlatformComponents components,
            @NonNull final Platform platform,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final SavedStateController savedStateController,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final NodeId selfId,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {
        final ReconnectCoordinator reconnectCoordinator = new ReconnectCoordinator(components, platformCoordinator);

        final ReconnectController reconnectController = new ReconnectController(
                configuration,
                time,
                currentRoster,
                platform,
                reconnectCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                reservedSignedStateResultPromise,
                selfId,
                fallenBehindMonitor,
                new DefaultSignedStateValidator());

        final Thread reconnectControllerThread = new ThreadConfiguration(AdHocThreadManager.getStaticThreadManager())
                .setComponent("platform-core")
                .setThreadName("reconnectController")
                .setRunnable(reconnectController)
                .build(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            reconnectController.stopReconnectLoop();
            reconnectControllerThread.interrupt();
        }));
    }
}
