// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import org.hiero.consensus.threading.manager.ThreadManager;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.reconnect.FallenBehindMonitor;
import com.swirlds.platform.reconnect.ReconnectStatePeerProtocol;
import com.swirlds.platform.reconnect.ReconnectStateTeacherThrottle;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

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

    private final Time time;
    private final PlatformContext platformContext;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise;
    private final StateLifecycleManager stateLifecycleManager;

    public ReconnectStateSyncProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectStateTeacherThrottle reconnectStateTeacherThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final FallenBehindMonitor fallenBehindManager,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final StateLifecycleManager stateLifecycleManager) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.reconnectStateTeacherThrottle = Objects.requireNonNull(reconnectStateTeacherThrottle);
        this.lastCompleteSignedState = Objects.requireNonNull(lastCompleteSignedState);
        this.reconnectSocketTimeout = Objects.requireNonNull(reconnectSocketTimeout);
        this.reconnectMetrics = Objects.requireNonNull(reconnectMetrics);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.time = Objects.requireNonNull(platformContext.getTime());
        this.reservedSignedStateResultPromise = Objects.requireNonNull(reservedSignedStateResultPromise);
        this.stateLifecycleManager = Objects.requireNonNull(stateLifecycleManager);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReconnectStatePeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new ReconnectStatePeerProtocol(
                platformContext,
                threadManager,
                Objects.requireNonNull(peerId),
                reconnectStateTeacherThrottle,
                lastCompleteSignedState,
                reconnectSocketTimeout,
                reconnectMetrics,
                fallenBehindManager,
                platformStatus::get,
                time,
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
