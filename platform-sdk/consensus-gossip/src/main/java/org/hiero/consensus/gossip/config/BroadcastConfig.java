// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration of the broadcast gossip algorithm
 *
 * @param enableBroadcast              enable simplistic broadcast, where all self-events are broadcast to all
 *                                     neighbours
 * @param disablePingThreshold         if ping against peer breaches that level, we disable the broadcast for some time,
 *                                     as sync is more efficient at that point
 * @param throttleOutputQueueThreshold if the output queue of rpc is bigger than this value, we disable the broadcast
 *                                     for some time, as we don't want to add additional load on network traffic.
 *                                     Therefore we leave sync to manage it temporarily.
 * @param pauseOnLag                   amount of time for which broadcast will be paused if communication overload is
 *                                     detected
 * @param rpcSleepAfterSyncWhileBroadcasting Override for {@link SyncConfig#rpcSleepAfterSync()} when broadcast is running.
 */
@ConfigData("broadcast")
public record BroadcastConfig(
        @ConfigProperty(defaultValue = "false") boolean enableBroadcast,
        @ConfigProperty(defaultValue = "900ms") Duration disablePingThreshold,
        @ConfigProperty(defaultValue = "200") int throttleOutputQueueThreshold,
        @ConfigProperty(defaultValue = "30s") Duration pauseOnLag,
        @ConfigProperty(defaultValue = "100ms") Duration rpcSleepAfterSyncWhileBroadcasting) {}
