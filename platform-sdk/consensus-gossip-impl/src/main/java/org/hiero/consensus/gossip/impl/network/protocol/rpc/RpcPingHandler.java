// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.hedera.hapi.platform.message.GossipPing;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.impl.network.NetworkMetrics;
import org.hiero.consensus.model.node.NodeId;

/**
 * Helper class sibling to {@link RpcPeerProtocol} to handle ping logic between nodes
 */
final class RpcPingHandler {

    private static final Logger logger = LogManager.getLogger(RpcPingHandler.class);

    /**
     * Platform time
     */
    private final Time time;
    /**
     * Timestamp for each ping correlation id, so ping time can be measured after reply
     */
    private final ConcurrentMap<Long, Long> sentPings = new ConcurrentHashMap<>();

    /**
     * Network metrics to register data about communication traffic and latencies
     */
    private final NetworkMetrics networkMetrics;

    /**
     * Node if against which we are measuring ping
     */
    private final NodeId remotePeerId;

    /**
     * Peer protocol which is handling ping communication
     */
    private final RpcPeerProtocol rpcPeerProtocol;

    /**
     * How often pings should be sent
     */
    private final long pingPeriod;

    /**
     * Increasing counter for ping correlation id
     */
    private long pingId = 1;

    /**
     * Last time ping was sent, to keep track to avoid spamming network with ping requests
     */
    private long lastPingInitiationTime;

    /**
     * @param time            the {@link Time} instance for the platformeturns the {@link Time} instance for the
     *                        platform
     * @param networkMetrics  network metrics to register data about communication traffic and latencies
     * @param remotePeerId    the id of the peer being synced with in this protocol
     * @param rpcPeerProtocol peer protocol which is handling ping communication
     */
    RpcPingHandler(
            final @NonNull Time time,
            final NetworkMetrics networkMetrics,
            final NodeId remotePeerId,
            final RpcPeerProtocol rpcPeerProtocol,
            final @NonNull Duration pingPeriod) {
        this.time = Objects.requireNonNull(time);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.remotePeerId = Objects.requireNonNull(remotePeerId);
        this.rpcPeerProtocol = Objects.requireNonNull(rpcPeerProtocol);
        this.pingPeriod = TimeUnit.NANOSECONDS.convert(pingPeriod);
    }

    void handleIncomingPing(final GossipPing ping) {
        rpcPeerProtocol.sendPingReply(ping);
    }

    /**
     * Check if enough time has passed since last ping initiation
     *
     * @return ping to be sent or null if not enough time has passed
     */
    GossipPing possiblyInitiatePing() {
        final long timestamp = time.nanoTime();
        if ((timestamp - lastPingInitiationTime) < pingPeriod) {
            return null;
        }
        this.lastPingInitiationTime = timestamp;
        final GossipPing ping = new GossipPing(pingId++);
        sentPings.put(ping.correlationId(), timestamp);
        return ping;
    }

    /**
     * Called when ping reply was received by network layer
     *
     * @param pingReply reply to our ping
     * @return amount of nanoseconds which has passed since we have sent that ping request
     */
    long handleIncomingPingReply(@NonNull final GossipPing pingReply) {
        final Long original = sentPings.remove(pingReply.correlationId());
        if (original == null) {
            logger.error(
                    NETWORK.getMarker(),
                    "Received unexpected gossip ping reply from peer {} for correlation id {}",
                    remotePeerId,
                    pingReply.correlationId());
            return 0L;
        } else {
            final long lastPingNanos = (time.nanoTime() - original);
            logger.debug(NETWORK.getMarker(), "Ping {}", lastPingNanos);
            networkMetrics.recordPingTime(remotePeerId, lastPingNanos);
            return lastPingNanos;
        }
    }
}
