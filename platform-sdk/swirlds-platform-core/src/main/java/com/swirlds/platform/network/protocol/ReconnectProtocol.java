// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.reconnect.ReconnectPeerProtocol;
import com.swirlds.platform.reconnect.ReconnectSyncHelper;
import com.swirlds.platform.reconnect.ReconnectThrottle;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implementation of a factory for reconnect protocol
 */
public class ReconnectProtocol implements Protocol {

    private final ReconnectThrottle reconnectThrottle;
    private final Supplier<ReservedSignedState> lastCompleteSignedState;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics reconnectMetrics;
    private final ThreadManager threadManager;
    private final FallenBehindManager fallenBehindManager;
    private final PlatformStateFacade platformStateFacade;

    private final Time time;
    private final PlatformContext platformContext;
    private final AtomicReference<PlatformStatus> platformStatus = new AtomicReference<>(PlatformStatus.STARTING_UP);
    private final ReconnectSyncHelper reconnectSyncHelper;

    public ReconnectProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final ReconnectThrottle reconnectThrottle,
            @NonNull final Supplier<ReservedSignedState> lastCompleteSignedState,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics reconnectMetrics,
            @NonNull final ReconnectSyncHelper reconnectSyncHelper,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final PlatformStateFacade platformStateFacade) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.reconnectThrottle = Objects.requireNonNull(reconnectThrottle);
        this.lastCompleteSignedState = Objects.requireNonNull(lastCompleteSignedState);
        this.reconnectSocketTimeout = Objects.requireNonNull(reconnectSocketTimeout);
        this.reconnectMetrics = Objects.requireNonNull(reconnectMetrics);
        this.reconnectSyncHelper = Objects.requireNonNull(reconnectSyncHelper);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.platformStateFacade = platformStateFacade;
        this.time = Objects.requireNonNull(platformContext.getTime());
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReconnectPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new ReconnectPeerProtocol(
                platformContext,
                threadManager,
                Objects.requireNonNull(peerId),
                reconnectThrottle,
                lastCompleteSignedState,
                reconnectSocketTimeout,
                reconnectMetrics,
                reconnectSyncHelper,
                fallenBehindManager,
                platformStatus::get,
                time,
                platformStateFacade);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        platformStatus.set(status);
    }
}
