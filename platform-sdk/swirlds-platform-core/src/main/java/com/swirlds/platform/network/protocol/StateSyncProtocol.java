// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.gossip.FallenBehindMonitor;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.reconnect.StateSyncPeerProtocol;
import com.swirlds.platform.reconnect.StateSyncThrottle;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implementation of a factory for reconnect protocol
 */
public class StateSyncProtocol implements Protocol {

    private final StateSyncThrottle stateSyncThrottle;
    private final Supplier<ReservedSignedState> lastCompleteSignedState;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics reconnectMetrics;
    private final ThreadManager threadManager;
    private final FallenBehindMonitor fallenBehindManager;
    private final PlatformStateFacade platformStateFacade;

    private final Time time;
    private final PlatformContext platformContext;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private final ReservedSignedStatePromise reservedSignedStatePromise;
    private final SwirldStateManager swirldStateManager;
    private final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap;

    public StateSyncProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final StateSyncThrottle stateSyncThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final FallenBehindMonitor fallenBehindManager,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final ReservedSignedStatePromise reservedSignedStatePromise,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.stateSyncThrottle = Objects.requireNonNull(stateSyncThrottle);
        this.lastCompleteSignedState = Objects.requireNonNull(lastCompleteSignedState);
        this.reconnectSocketTimeout = Objects.requireNonNull(reconnectSocketTimeout);
        this.reconnectMetrics = Objects.requireNonNull(reconnectMetrics);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.platformStateFacade = platformStateFacade;
        this.time = Objects.requireNonNull(platformContext.getTime());
        this.reservedSignedStatePromise = Objects.requireNonNull(reservedSignedStatePromise);
        this.swirldStateManager = Objects.requireNonNull(swirldStateManager);
        this.createStateFromVirtualMap = Objects.requireNonNull(createStateFromVirtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public StateSyncPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new StateSyncPeerProtocol(
                platformContext,
                threadManager,
                Objects.requireNonNull(peerId),
                stateSyncThrottle,
                lastCompleteSignedState,
                reconnectSocketTimeout,
                reconnectMetrics,
                fallenBehindManager,
                platformStatus::get,
                time,
                platformStateFacade,
                reservedSignedStatePromise,
                swirldStateManager,
                createStateFromVirtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        platformStatus.set(status);
    }
}
