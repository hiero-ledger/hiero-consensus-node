// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import java.util.List;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.Topology;

/**
 * Interface representing the topology of a Turtle network.
 * It extends the {@link Topology} interface and provides a method to retrieve Turtle nodes.
 *
 * @param <T> the type of nodes in the topology, which must extend {@link Node}
 */
public interface TopologyImplementation<T extends Node> extends Topology {

    /**
     * Returns the nodes in the topology cast to the specific {@link Node} type.
     *
     * @return a list of nodes in the topology
     */
    List<T> nodesImpl();
}
