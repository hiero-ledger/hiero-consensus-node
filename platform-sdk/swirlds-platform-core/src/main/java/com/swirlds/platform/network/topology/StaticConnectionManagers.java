// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.topology;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.swirlds.platform.network.*;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Pre-builds connection managers for the supplied topology, does not allow changes at runtime
 */
public class StaticConnectionManagers {
    private static final Logger logger = LogManager.getLogger(StaticConnectionManagers.class);
    private final ConcurrentHashMap<NodeId, ConnectionManager> connectionManagers;
    private final OutboundConnectionCreator connectionCreator;

    public StaticConnectionManagers(final NetworkTopology topology, final OutboundConnectionCreator connectionCreator) {
        this.connectionCreator = connectionCreator;
        connectionManagers = new ConcurrentHashMap<>();
        for (final NodeId neighbor : topology.getNeighbors()) {
            updateManager(topology, neighbor);
        }
    }

    private void updateManager(NetworkTopology topology, NodeId neighbor) {
        if (topology.shouldConnectToMe(neighbor)) {
            connectionManagers.put(neighbor, new InboundConnectionManager());
        } else if (topology.shouldConnectTo(neighbor)) {
            connectionManagers.put(neighbor, new LegacyOutboundConnectionManager(neighbor, connectionCreator));
        } else {
            connectionManagers.remove(neighbor);
        }
    }

    /**
     * Returns pre-allocated connection for given node.
     *
     * @param id node id to retrieve connection for
     * @return inbound or outbound connection for that node, depending on the topology, or null if such node id is unknown
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
    public void newConnection(final Connection newConn) throws InterruptedException {

        final ConnectionManager cs = connectionManagers.get(newConn.getOtherId());
        if (cs == null || cs.isOutbound()) {
            logger.error(EXCEPTION.getMarker(), "Unexpected new connection {}", newConn.getDescription());
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
}
