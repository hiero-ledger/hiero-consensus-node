// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import org.hiero.otter.fixtures.Node;

/**
 * Common interface for all types of network cliques. Provides basic clique functionality shared by all
 * implementations.
 */
@SuppressWarnings("unused")
public interface Clique {

    /**
     * Gets the set of nodes in this clique.
     *
     * @return the nodes in this clique
     */
    @NonNull
    Set<Node> nodes();


    /**
     * Gets the latency range for external connections.
     *
     * @return the external latency range or {@code null} if not set
     */
    @Nullable
    LatencyRange externalLatency();

    /**
     * Gets the bandwidth limit for external connections.
     *
     * @return the external bandwidth limit or {@code null} if not set
     */
    @Nullable
    BandwidthLimit externalBandwidth();

    /**
     * Checks if the clique contains the specified node.
     *
     * @param node the node to check
     * @return {@code true) if the node is in this clique, {@code false} otherwise
     */
    default boolean contains(@NonNull final Node node) {
        return nodes().contains(node);
    }

    /**
     * Gets the number of nodes in this clique.
     *
     * @return the size of the clique
     */
    default int size() {
        return nodes().size();
    }
}
