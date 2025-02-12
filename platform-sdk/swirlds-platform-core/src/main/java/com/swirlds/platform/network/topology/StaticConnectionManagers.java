/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.network.topology;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.NETWORK;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionManager;
import com.swirlds.platform.network.InboundConnectionManager;
import com.swirlds.platform.network.OutboundConnectionManager;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Pre-builds connection managers for the supplied topology, does not allow changes at runtime
 */
public class StaticConnectionManagers {
    private static final Logger logger = LogManager.getLogger(StaticConnectionManagers.class);
    private final Map<NodeId, ConnectionManager> connectionManagers;

    public StaticConnectionManagers(final NetworkTopology topology, final OutboundConnectionCreator connectionCreator) {
        // is thread safe because it never changes
        connectionManagers = new HashMap<>();
        for (final NodeId neighbor : topology.getNeighbors()) {
            if (topology.shouldConnectToMe(neighbor)) {
                connectionManagers.put(neighbor, new InboundConnectionManager());
            }
            if (topology.shouldConnectTo(neighbor)) {
                connectionManagers.put(neighbor, new OutboundConnectionManager(neighbor, connectionCreator));
            }
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
