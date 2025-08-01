// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Set;
import org.hiero.otter.fixtures.Node;

/**
 * Common interface for all types of network cliques. Provides basic clique functionality shared by all
 * implementations.
 */
@SuppressWarnings("unused")
public abstract class Clique {

    /**
     * Creates a clique with throttled external bandwidth.
     *
     * <p>If a node is already part of a partition or clique, it will be removed before being added to the new clique.
     *
     * @param nodes the nodes to include in the clique
     * @param externalBandwidth the bandwidth limit for external connections
     * @return a new ThrottledClique object
     */
    @NonNull
    public static ThrottledClique of(
            @NonNull final Collection<Node> nodes, @NonNull final BandwidthLimit externalBandwidth) {
        return new ThrottledClique(nodes, externalBandwidth);
    }

    /**
     * Creates a clique with increased external latency.
     *
     * <p>If a node is already part of a partition or clique, it will be removed before being added to the new clique.
     *
     * @param nodes the nodes to include in the clique
     * @param externalLatency the additional latency for external connections
     * @return a new SlowClique object
     */
    @NonNull
    public static SlowClique of(@NonNull final Collection<Node> nodes, @NonNull final LatencyRange externalLatency) {
        return new SlowClique(nodes, externalLatency);
    }

    private final Set<Node> nodes;

    private Clique(@NonNull final Collection<Node> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Clique cannot be empty");
        }
        this.nodes = Set.copyOf(nodes);
    }

    /**
     * Gets the set of nodes in this clique.
     *
     * @return the nodes in this clique
     */
    @NonNull
    public Set<Node> nodes() {
        return nodes;
    }

    /**
     * Checks if the clique contains the specified node.
     *
     * @param node the node to check
     * @return {@code true) if the node is in this clique, {@code false} otherwise
     */
    public boolean contains(@NonNull final Node node) {
        return nodes.contains(node);
    }

    /**
     * Gets the number of nodes in this clique.
     *
     * @return the size of the clique
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Represents a network clique with throttled external bandwidth. Nodes within the clique have full connectivity,
     * while external connections have bandwidth restrictions.
     */
    public static class ThrottledClique extends Clique {

        private final BandwidthLimit externalBandwidth;

        private ThrottledClique(
                @NonNull final Collection<Node> nodes, @NonNull final BandwidthLimit externalBandwidth) {
            super(nodes);
            if (externalBandwidth.isUnlimited()) {
                throw new IllegalArgumentException("External bandwidth cannot be unlimited");
            }
            this.externalBandwidth = externalBandwidth;
        }

        /**
         * Gets the bandwidth limit for external connections.
         *
         * @return the external bandwidth limit
         */
        @NonNull
        public BandwidthLimit externalBandwidth() {
            return externalBandwidth;
        }
    }

    /**
     * Represents a network clique with increased external latency. Nodes within the clique have full connectivity,
     * while external connections have latency in the specified range.
     */
    public static class SlowClique extends Clique {

        private final LatencyRange externalLatency;

        private SlowClique(@NonNull final Collection<Node> nodes, @NonNull final LatencyRange externalLatency) {
            super(nodes);
            this.externalLatency = requireNonNull(externalLatency);
        }

        /**
         * Gets the latency range for external connections.
         *
         * @return the external latency range
         */
        @NonNull
        public LatencyRange externalLatency() {
            return externalLatency;
        }
    }
}
