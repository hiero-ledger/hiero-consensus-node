// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.Node;

/**
 * Represents a network partition containing a group of nodes.
 * Nodes within the partition are connected to each other but isolated from external nodes.
 */
public interface Partition {

    /**
     * Gets the nodes in this partition.
     *
     * <p>Note: While the returned set is unmodifiable, the {@link Set} can still change if the partitions are changed
     *
     * @return an unmodifiable set of nodes in this partition
     */
    @NonNull
    Set<Node> nodes();

    /**
     * Checks if the partition contains the specified node.
     *
     * @param node the node to check
     * @return true if the node is in this partition
     */
    boolean contains(@NonNull final Node node);

    /**
     * Gets the number of nodes in this partition.
     *
     * @return the size of the partition
     */
    int size();
}
