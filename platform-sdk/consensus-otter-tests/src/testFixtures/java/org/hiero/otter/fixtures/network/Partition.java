// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Set;
import org.hiero.otter.fixtures.Node;

/**
 * Represents a network partition containing a group of nodes.
 * Nodes within the partition are connected to each other but isolated from external nodes.
 */
public class Partition {

    /**
     * Creates a partition from a collection of nodes.
     *
     * @param nodes the nodes to include in the partition
     * @return a new Partition object
     * @throws NullPointerException if {@code nodes} is {@code null}
     * @throws IllegalArgumentException if {@code nodes} is empty
     */
    public static Partition of(@NonNull final Collection<? extends Node> nodes) {
        return new Partition(nodes);
    }

    private final Set<Node> nodes;

    private Partition(@NonNull final Collection<? extends Node> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Partition cannot be empty");
        }
        this.nodes = Set.copyOf(nodes);
    }

    /**
     * Gets the nodes in this partition.
     *
     * @return an unmodifiable set of nodes in this partition
     */
    @NonNull
    public Set<Node> nodes() {
        return nodes;
    }

    /**
     * Checks if the partition contains the specified node.
     *
     * @param node the node to check
     * @return true if the node is in this partition
     */
    public boolean contains(@NonNull final Node node) {
        return nodes.contains(requireNonNull(node));
    }

    /**
     * Gets the number of nodes in this partition.
     *
     * @return the size of the partition
     */
    public int size() {
        return nodes.size();
    }
}
