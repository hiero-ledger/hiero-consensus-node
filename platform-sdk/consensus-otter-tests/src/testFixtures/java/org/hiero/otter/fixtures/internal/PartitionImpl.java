// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.Partition;

public class PartitionImpl implements Partition {
    private final Set<Node> nodes = new HashSet<>();

    /**
     * Creates a partition from a collection of nodes.
     *
     * @param nodes the nodes to include in the partition
     */
    public PartitionImpl(@NonNull final Collection<? extends Node> nodes) {
        this.nodes.addAll(nodes);
    }

    /**
     * Gets the nodes in this partition.
     *
     * <p>Note: While the returned set is unmodifiable, the {@link Set} can still change if the partitions are
     * changed
     *
     * @return an unmodifiable set of nodes in this partition
     */
    @NonNull
    public Set<Node> nodes() {
        return Collections.unmodifiableSet(nodes);
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
