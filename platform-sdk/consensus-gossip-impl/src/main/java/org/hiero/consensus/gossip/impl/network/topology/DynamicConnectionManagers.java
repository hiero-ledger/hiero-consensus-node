// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.topology;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.ConnectionManager;
import org.hiero.consensus.gossip.impl.network.ConnectionTracker;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.ShmConnectionManager;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * Holds all the connection managers for incoming and outgoing connections. Able to react to change in peers/topology
 * though {@link #addRemovePeers(List, List, StaticTopology)} method.
 */
public class DynamicConnectionManagers {

    private static final Logger logger = LogManager.getLogger(DynamicConnectionManagers.class);
    private final Configuration configuration;
    private final Time time;
    private final ConcurrentHashMap<NodeId, ConnectionManager> connectionManagers = new ConcurrentHashMap<>();
    private final NodeId selfId;
    private final ConnectionTracker connectionTracker;
    private final KeysAndCerts ownKeysAndCerts;
    private final ConnectionManagerFactory connectionManagerFactory;

    /**
     * Creates new dynamic connection managers holder.
     *
     * @param configuration platform configuration
     * @param time source of time
     * @param selfId self's node id
     * @param peers the list of peers
     * @param connectionTracker connection tracker for all platform connections
     * @param ownKeysAndCerts private keys and public certificates
     * @param topology current topology of connecions
     * @param connectionManagerFactory factory to create custom inbound and oubound connection managers
     */
    public DynamicConnectionManagers(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final List<PeerInfo> peers,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final KeysAndCerts ownKeysAndCerts,
            @NonNull final NetworkTopology topology,
            @NonNull final ConnectionManagerFactory connectionManagerFactory) {
        this.configuration = requireNonNull(configuration);
        this.time = requireNonNull(time);
        this.selfId = requireNonNull(selfId);
        this.connectionTracker = requireNonNull(connectionTracker);
        this.ownKeysAndCerts = requireNonNull(ownKeysAndCerts);
        this.connectionManagerFactory = requireNonNull(connectionManagerFactory);
        for (PeerInfo peer : peers) {
            updateManager(topology, peer);
        }
    }

    /**
     * Returns pre-allocated connection for given node.
     *
     * @param id node id to retrieve connection for
     * @return inbound or outbound connection for that node, depending on the topology, or null if such node id is
     * unknown
     */
    public ConnectionManager getManager(final NodeId id) {
        return connectionManagers.get(id);
    }

    /**
     * Called when a new connection is established by a peer. After startup, we don't expect this to be called unless
     * there are networking issues. The connection is passed on to the appropriate connection manager if valid.
     *
     * @param newConn a new connection that has been established
     */
    public void newConnection(@NonNull final Connection newConn) throws InterruptedException {

        final ConnectionManager cs = connectionManagers.get(newConn.getOtherId());
        if (cs == null) {
            logger.error(EXCEPTION.getMarker(), "Unexpected new connection {}", newConn.getDescription());
            newConn.disconnect();
            return;
        }

        if (cs.isOutbound()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Unexpected new connection, we should be connecting to them instead {}",
                    newConn.getDescription());
            newConn.disconnect();
            return;
        }

        logger.debug(NETWORK.getMarker(), "{} accepted connection from {}", newConn.getSelfId(), newConn.getOtherId());
        try {
            cs.newConnection(newConn);
        } catch (final InterruptedException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Interrupted while handling over new connection {}",
                    newConn.getDescription(),
                    e);
            newConn.disconnect();
            throw e;
        }
    }

    /**
     * Update information about possible peers; In the case data for the same peer changes (one with the same nodeId),
     * it should be present in both removed and added lists, with old data in removed and fresh data in added.
     *
     * @param added    peers to add
     * @param removed  peers to remove
     * @param topology new topology with all the changes applied
     */
    public void addRemovePeers(
            @NonNull final List<PeerInfo> added,
            @NonNull final List<PeerInfo> removed,
            @NonNull final StaticTopology topology) {
        for (PeerInfo peerInfo : removed) {
            connectionManagers.remove(peerInfo.nodeId());
        }
        for (PeerInfo peerInfo : added) {
            updateManager(topology, peerInfo);
        }
    }

    private void updateManager(@NonNull final NetworkTopology topology, @NonNull final PeerInfo otherPeer) {
//        if (topology.shouldConnectToMe(otherPeer.nodeId())) {
//            connectionManagers.put(
//                    otherPeer.nodeId(), connectionManagerFactory.createInboundConnectionManager(otherPeer));
//        } else if (topology.shouldConnectTo(otherPeer.nodeId())) {
//            connectionManagers.put(
//                    otherPeer.nodeId(),
//                    connectionManagerFactory.createOutboundConnectionManager(
//                            configuration, time, selfId, otherPeer, connectionTracker, ownKeysAndCerts));
//        } else {
//            connectionManagers.remove(otherPeer.nodeId());
//        }

        connectionManagers.put(otherPeer.nodeId(), new ShmConnectionManager(configuration, time, selfId, otherPeer, connectionTracker, ownKeysAndCerts));
    }
}
