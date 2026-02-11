// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import java.util.List;
import java.util.Map;
import org.hiero.consensus.gossip.config.NetworkEndpoint;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.container.network.NetworkBehavior;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.jspecify.annotations.NonNull;

public class NoOpNetworkBehavior implements NetworkBehavior {

    /**
     * {@inheritDoc} Does nothing in this implementation.
     */
    @Override
    public void onConnectionsChanged(final List<Node> nodes, final Map<ConnectionKey, ConnectionState> connections) {}

    /**
     * {@inheritDoc}
     * <p>
     * Throws {@link UnsupportedOperationException} in this implementation.
     */
    @Override
    public NetworkEndpoint getProxyEndpoint(@NonNull final Node sender, @NonNull final Node receiver) {
        throw new UnsupportedOperationException("No-op network behavior does not support proxy endpoints.");
    }
}
