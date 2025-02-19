/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.gossip.modular;

import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.system.status.PlatformStatus;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Temporary class made as a side effect of refactoring of SyncGossip
 * Contains all the pieces of state which are accessed between various protocols
 * For each of them, reasonable solution has to be found, how to wire it properly (either through rest of the system, or reduce dependency on shared state between the protocols)
 *
 * @param networkMetrics        metrics to register data about communication traffic and latencies
 * @param syncPermitProvider    manages sync permits, to avoid spamming too many operations at once
 * @param shadowgraph           mirror copy of all the events
 * @param syncManager           who we need to sync with, and whether we need to reconnect
 * @param gossipHalted          flag to stop gossip when reconnection
 * @param shadowgraphExecutor   reference to shadowgraph thread to be started by controller
 * @param currentPlatformStatus used to tell gossip the status of the platform
 * @param receivedEventHandler  output wiring to call when event is received from neighbour
 * @param fallenBehindCallback  callback to run on first case of falling behind, to initialize reconnection threads
 */
public record SyncGossipSharedProtocolState(
        NetworkMetrics networkMetrics,
        SyncPermitProvider syncPermitProvider,
        Shadowgraph shadowgraph,
        SyncManagerImpl syncManager,
        AtomicBoolean gossipHalted,
        CachedPoolParallelExecutor shadowgraphExecutor,
        AtomicReference<PlatformStatus> currentPlatformStatus,
        Consumer<PlatformEvent> receivedEventHandler,
        AtomicReference<Runnable> fallenBehindCallback) {}
