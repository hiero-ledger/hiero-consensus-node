// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration of per-peer inbound traffic shaping for the rpc gossip protocol.
 *
 * @param enabled            compute and report the per-peer byte budget; when false the shaper is entirely inert
 * @param enforce            additionally pause reading from a peer which is over budget. When false the shaper runs
 *                           in shadow mode: everything is measured and reported, nothing is slowed down
 * @param peerBytesPerSecond sustained inbound rate permitted per peer, in bytes per second. Uniform across all
 *                           peers; not weighted by stake or role
 * @param peerBurstBytes     inbound bytes a peer may consume instantaneously before the sustained rate applies. Also
 *                           acts as the connection grace period, since a fresh shaper starts with the full burst
 *                           available
 * @param maxReadDelay       maximum time reading from a peer may be paused in one step. This is a liveness bound
 *                           rather than a tuning knob: while reads are paused we do not answer the peer's pings, so
 *                           this must stay well below {@link BroadcastConfig#disablePingThreshold()} or the peer
 *                           will treat us as unhealthy
 * @param maxMessageBytes    maximum permitted encoded size of a single gossip message, in bytes
 * @param lowWatermark       fraction of the burst budget above which a rate limited warning is logged
 * @param highWatermark      fraction of the burst budget above which an serious error is reported (and in future, peer disconnected)
 * @param reportInterval     minimum interval between repeated warnings or evidence records for the same peer
 */
@ConfigData("trafficShaping")
public record TrafficShapingConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "false") boolean enforce,
        @ConfigProperty(defaultValue = "20000000") long peerBytesPerSecond,
        @ConfigProperty(defaultValue = "200000000") long peerBurstBytes,
        @ConfigProperty(defaultValue = "200ms") Duration maxReadDelay,
        @ConfigProperty(defaultValue = "45000000") int maxMessageBytes,
        @ConfigProperty(defaultValue = "0.6") double lowWatermark,
        @ConfigProperty(defaultValue = "0.9") double highWatermark,
        @ConfigProperty(defaultValue = "1m") Duration reportInterval) {}
