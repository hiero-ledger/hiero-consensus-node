// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.google.common.collect.Maps;
import com.hedera.hapi.platform.message.GossipPing;
import com.swirlds.base.time.Time;
import com.swirlds.platform.network.NetworkMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    private final ConcurrentMap<Long, GossipPing> sentPings = Maps.newConcurrentMap();

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
            final RpcPeerProtocol rpcPeerProtocol) {
        this.time = Objects.requireNonNull(time);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.remotePeerId = Objects.requireNonNull(remotePeerId);
        this.rpcPeerProtocol = Objects.requireNonNull(rpcPeerProtocol);
    }

    /**
     * Increasing counter for ping correlation id
     */
    private long pingId = 1;

    /**
     * Last time ping was sent, to keep track to avoid spamming network with ping requests
     */
    private long lastPingTime;

    void handleIncomingPing(final GossipPing ping) {
        final GossipPing reply = new GossipPing(time.currentTimeMillis(), ping.correlationId());
        rpcPeerProtocol.sendPingReply(reply);
    }

    /**
     * Check if enough time has passed since last ping initiation
     *
     * @return ping to be sent or null if not enough time has passed
     */
    GossipPing possiblyInitiatePing() {
        final long timestamp = time.currentTimeMillis();
        if ((timestamp - lastPingTime) < 1000) {
            return null;
        }
        this.lastPingTime = timestamp;
        final GossipPing ping = new GossipPing(timestamp, pingId++);
        sentPings.put(ping.correlationId(), ping);
        return ping;
    }

    /**
     * Called when ping reply was received by network layer
     *
     * @param pingReply reply to our ping
     */
    void handleIncomingPingReply(final GossipPing pingReply) {
        final GossipPing original = sentPings.remove(pingReply.correlationId());
        if (original == null) {
            logger.error(
                    NETWORK.getMarker(),
                    "Received unexpected gossip ping reply from peer {} for correlation id {}",
                    remotePeerId,
                    pingReply.correlationId());
        } else {
            // don't trust remote timestamp for measuring ping
            logger.debug(NETWORK.getMarker(), "Ping {}", time.currentTimeMillis() - original.timestamp());
            networkMetrics.recordPingTime(remotePeerId, (time.currentTimeMillis() - original.timestamp()) * 1_000_000);
        }
    }
}
