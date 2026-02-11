// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.gossip.config.NetworkEndpoint;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;

/**
 * Generic interface to allows controlling network behavior. Main implementation is {@link ProxyNetworkBehavior} using
 * Toxiproxy.
 */
public interface NetworkBehavior {
    /**
     * Updates the connections in the network based on the provided nodes and connection data.
     *
     * @param nodes          the list of nodes in the network
     * @param newConnections a map of connections representing the current state of the network
     */
    void onConnectionsChanged(List<Node> nodes, Map<ConnectionKey, ConnectionState> newConnections);

    /**
     * Gets the {@link NetworkEndpoint} of the proxy for a connection between two nodes.
     *
     * @param sender   the node that sends messages
     * @param receiver the node that receives messages
     * @return the endpoint of the proxy
     * @throws NullPointerException  if either sender or receiver is {@code null}
     * @throws IllegalStateException if the connection cannot be found
     */
    NetworkEndpoint getProxyEndpoint(@NonNull final Node sender, @NonNull final Node receiver);
}
