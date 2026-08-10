// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.reconnect.ReconnectModule;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.ConsensusLayerAdapterBuildingBlocks;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.concurrent.manager.AdHocThreadManager;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.PeerProtocolFactory;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * The default implementation of {@link ReconnectModule}.
 */
public class DefaultReconnectModule implements ReconnectModule {

    private ReconnectPeerProtocolFactory reconnectPeerProtocolFactory;

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final Roster currentRoster,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final ConsensusLayerAdapterBuildingBlocks buildingBlocks,
            @NonNull final Platform platform,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final NodeId selfId) {

        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);
        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(reconnectConfig, time);
        final ReconnectMetrics reconnectMetrics = new ReconnectMetrics(metrics);
        final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise =
                new BlockingResourceProvider<>();
        reconnectPeerProtocolFactory = new ReconnectPeerProtocolFactory(
                configuration,
                metrics,
                time,
                threadManager,
                reconnectStateTeacherThrottle,
                lastCompleteSignedState,
                reconnectConfig.socketTimeout(),
                reconnectMetrics,
                buildingBlocks.fallenBehindMonitor(),
                reservedSignedStateResultPromise,
                stateLifecycleManager,
                buildingBlocks.platformStatusReference()::get
        );

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
                reservedSignedStateResultPromise,
                selfId,
                buildingBlocks.fallenBehindMonitor(),
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

    @NonNull
    @Override
    public PeerProtocolFactory getReconnectPeerProtocolFactory() {
        return requireNonNull(reconnectPeerProtocolFactory, "Not initialized");
    }
}
