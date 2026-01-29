// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.reconnect.api.ProtocolFactory;
import com.swirlds.platform.reconnect.api.ReservedSignedStateResult;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

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
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final StateLifecycleManager stateLifecycleManager) {
        final ReconnectConfig reconnectConfig =
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class);

        final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle =
                new ReconnectStateTeacherThrottle(reconnectConfig, platformContext.getTime());

        final ReconnectMetrics reconnectMetrics = new ReconnectMetrics(platformContext.getMetrics());

        return new ReconnectStateSyncProtocol(
                platformContext,
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
