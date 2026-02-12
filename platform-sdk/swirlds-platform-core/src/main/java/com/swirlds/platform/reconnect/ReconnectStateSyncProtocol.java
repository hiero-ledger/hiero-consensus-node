// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This protocol is responsible for synchronizing a current state either local acting as lerner or remote acting as teacher.
 */
public class ReconnectStateSyncProtocol implements Protocol {

    private final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle;
    private final Supplier<ReservedSignedState> lastCompleteSignedState;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics reconnectMetrics;
    private final ThreadManager threadManager;
    private final FallenBehindMonitor fallenBehindManager;

    private final Configuration configuration;
    private final Metrics metrics;
    private final Time time;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise;
    private final StateLifecycleManager stateLifecycleManager;

    public ReconnectStateSyncProtocol(
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
            @NonNull final StateLifecycleManager stateLifecycleManager) {

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
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReconnectStatePeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new ReconnectStatePeerProtocol(
                configuration,
                metrics,
                time,
                threadManager,
                requireNonNull(peerId),
                reconnectStateTeacherThrottle,
                lastCompleteSignedState,
                reconnectSocketTimeout,
                reconnectMetrics,
                fallenBehindManager,
                platformStatus::get,
                reservedSignedStateResultPromise,
                stateLifecycleManager);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        platformStatus.set(status);
    }
}
