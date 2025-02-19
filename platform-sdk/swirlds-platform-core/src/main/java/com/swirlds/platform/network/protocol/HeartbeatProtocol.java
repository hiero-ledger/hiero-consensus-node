/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.network.protocol;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.gossip.modular.SyncGossipSharedProtocolState;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.heartbeats.HeartbeatPeerProtocol;
import com.swirlds.platform.network.NetworkMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;

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
     * @param sharedState       temporary class to share state between various protocols in modularized gossip, to be removed
     * @return constructed HeartbeatProtocol
     */
    public static HeartbeatProtocol create(PlatformContext platformContext, SyncGossipSharedProtocolState sharedState) {
        var syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        return new HeartbeatProtocol(
                Duration.ofMillis(syncConfig.syncProtocolHeartbeatPeriod()),
                sharedState.networkMetrics(),
                platformContext.getTime());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public HeartbeatPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new HeartbeatPeerProtocol(Objects.requireNonNull(peerId), heartbeatPeriod, networkMetrics, time);
    }
}
