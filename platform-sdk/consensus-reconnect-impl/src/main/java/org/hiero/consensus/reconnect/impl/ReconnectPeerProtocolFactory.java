// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.main.model.reconnect.PeerProtocolFactory;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This protocol is responsible for synchronizing a current state either local acting as lerner or remote acting as teacher.
 */
public class ReconnectPeerProtocolFactory implements PeerProtocolFactory {

    private final FallenBehindMonitor fallenBehindMonitor;

    private final Configuration configuration;
    private final Metrics metrics;
    private final Time time;
    private final Supplier<PlatformStatus> platformStatusSupplier;
    private final ThreadManager threadManager;
    private final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle;
    private final ReconnectMetrics reconnectMetrics;
    private final Supplier<ReservedSignedState> lastCompleteSignedState;
    private final Duration reconnectSocketTimeout;
    private final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise;
    private final StateLifecycleManager stateLifecycleManager;


    public ReconnectPeerProtocolFactory(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final StateLifecycleManager stateLifecycleManager,
            @NonNull final Supplier<PlatformStatus> platformStatusSupplier) {

        this.configuration = requireNonNull(configuration);
        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.threadManager = requireNonNull(threadManager);
        this.reconnectStateTeacherThrottle = requireNonNull(reconnectStateTeacherThrottle);
        this.lastCompleteSignedState = requireNonNull(lastCompleteSignedState);
        this.reconnectSocketTimeout = requireNonNull(reconnectSocketTimeout);
        this.reconnectMetrics = requireNonNull(reconnectMetrics);
        this.fallenBehindMonitor = requireNonNull(fallenBehindMonitor);
        this.reservedSignedStateResultPromise = requireNonNull(reservedSignedStateResultPromise);
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);
        this.platformStatusSupplier = requireNonNull(platformStatusSupplier);
    }

    @NonNull
    @Override
    public ReconnectPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new ReconnectPeerProtocol(
                configuration,
                metrics,
                time,
                threadManager,
                requireNonNull(peerId),
                reconnectStateTeacherThrottle,
                lastCompleteSignedState,
                reconnectSocketTimeout,
                reconnectMetrics,
                fallenBehindMonitor,
                reservedSignedStateResultPromise,
                stateLifecycleManager,
                platformStatusSupplier);
    }
}
