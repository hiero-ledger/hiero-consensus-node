// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.topology;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionManager;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Holds all the connection managers for incoming and outgoing connections. Able to react to change in peers/topology
 * though {@link #addRemovePeers(List, List, StaticTopology)} method.
 */
public class DynamicConnectionManagers {

    private static final Logger logger = LogManager.getLogger(DynamicConnectionManagers.class);
    private final ConcurrentHashMap<NodeId, ConnectionManager> connectionManagers = new ConcurrentHashMap<>();
    private final NodeId selfId;
    private final PlatformContext platformContext;
    private final ConnectionTracker connectionTracker;
    private final KeysAndCerts ownKeysAndCerts;
    private final ConnectionManagerFactory connectionManagerFactory;

    /**
     * @param selfId                   self's node id
     * @param peers                    the list of peers
     * @param platformContext          the platform context
     * @param connectionTracker        connection tracker for all platform connections
     * @param ownKeysAndCerts          private keys and public certificates
     * @param topology                 current topology of connecions
     * @param connectionManagerFactory factory to create custom inbound and oubound connection managers
     */
    public DynamicConnectionManagers(
            @NonNull final NodeId selfId,
            @NonNull final List<PeerInfo> peers,
            @NonNull final PlatformContext platformContext,
            @NonNull final ConnectionTracker connectionTracker,
            @NonNull final KeysAndCerts ownKeysAndCerts,
            @NonNull final NetworkTopology topology,
            @NonNull final ConnectionManagerFactory connectionManagerFactory) {
        this.selfId = Objects.requireNonNull(selfId);
        this.platformContext = Objects.requireNonNull(platformContext);
        this.connectionTracker = Objects.requireNonNull(connectionTracker);
        this.ownKeysAndCerts = Objects.requireNonNull(ownKeysAndCerts);
        this.connectionManagerFactory = Objects.requireNonNull(connectionManagerFactory);
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
        if (topology.shouldConnectToMe(otherPeer.nodeId())) {
            connectionManagers.put(
                    otherPeer.nodeId(), connectionManagerFactory.createInboundConnectionManager(otherPeer));
        } else if (topology.shouldConnectTo(otherPeer.nodeId())) {
            connectionManagers.put(
                    otherPeer.nodeId(),
                    connectionManagerFactory.createOutboundConnectionManager(
                            selfId, otherPeer, platformContext, connectionTracker, ownKeysAndCerts));
        } else {
            connectionManagers.remove(otherPeer.nodeId());
        }
    }
}
