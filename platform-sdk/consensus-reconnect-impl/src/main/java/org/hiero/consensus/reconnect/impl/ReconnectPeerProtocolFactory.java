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
import org.hiero.consensus.main.model.PeerProtocolFactory;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This protocol is responsible for synchronizing a current state either local acting as lerner or remote acting as teacher.
 */
public class ReconnectPeerProtocolFactory implements PeerProtocolFactory {

    private final FallenBehindMonitor fallenBehindManager;

    private final Configuration configuration;
    private final Metrics metrics;
    private final Time time;
    private final Supplier<PlatformStatus> platformStatusSupplier;

    public ReconnectPeerProtocolFactory(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final FallenBehindMonitor fallenBehindManager,
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
        this.fallenBehindManager = requireNonNull(fallenBehindManager);
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
                requireNonNull(peerId),
                executionProtocol,
                platformStatusSupplier,
                fallenBehindManager);
    }
}
