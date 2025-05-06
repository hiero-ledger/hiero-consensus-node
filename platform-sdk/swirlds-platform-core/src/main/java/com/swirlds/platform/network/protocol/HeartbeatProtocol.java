// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.heartbeats.HeartbeatPeerProtocol;
import com.swirlds.platform.network.NetworkMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Implementation of a factory for heartbeat protocol
 */
public class HeartbeatProtocol implements Protocol {

    /**
     * The period at which the heartbeat protocol should be executed
     */
    private final Duration heartbeatPeriod;

    /**
     * Network metrics, for recording roundtrip heartbeat time
     */
    private final NetworkMetrics networkMetrics;

    /**
     * Source of time
     */
    private final Time time;

    public HeartbeatProtocol(
            @NonNull final Duration heartbeatPeriod,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final Time time) {

        this.heartbeatPeriod = Objects.requireNonNull(heartbeatPeriod);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.time = Objects.requireNonNull(time);
    }

    /**
     * Utility method for creating HeartbeatProtocol from shared state, while staying compatible with pre-refactor code
     * @param platformContext   the platform context
     * @param networkMetrics  Network metrics, for recording roundtrip heartbeat time
     * @return constructed HeartbeatProtocol
     */
    public static HeartbeatProtocol create(
            @NonNull final PlatformContext platformContext, @NonNull final NetworkMetrics networkMetrics) {
        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        return new HeartbeatProtocol(
                Duration.ofMillis(syncConfig.syncProtocolHeartbeatPeriod()), networkMetrics, platformContext.getTime());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public HeartbeatPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new HeartbeatPeerProtocol(Objects.requireNonNull(peerId), heartbeatPeriod, networkMetrics, time);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus status) {
        // no-op, we don't care
    }
}
