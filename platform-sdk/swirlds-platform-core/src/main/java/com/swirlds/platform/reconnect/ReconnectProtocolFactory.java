// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.reconnect.api.ProtocolFactory;
import com.swirlds.platform.reconnect.api.ReservedSignedStateResult;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Factory for creating the {@link ReconnectStateSyncProtocol}.
 */
public class ReconnectProtocolFactory implements ProtocolFactory {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Protocol createProtocol(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final StateLifecycleManager stateLifecycleManager) {
        final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(reconnectConfig, time);

        final ReconnectMetrics reconnectMetrics = new ReconnectMetrics(metrics);

        return new ReconnectStateSyncProtocol(
                configuration,
                metrics,
                time,
                threadManager,
                reconnectStateTeacherThrottle,
                latestCompleteState,
                reconnectConfig.asyncStreamTimeout(),
                reconnectMetrics,
                fallenBehindMonitor,
                reservedSignedStateResultPromise,
                stateLifecycleManager);
    }
}
