package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a network topology.
 *
 * <p>This interface defines methods for adding nodes to a topology. The specific way nodes are connected and their
 * properties such as latency, jitter, and bandwidth depend on the implementation of the topology.
 */
public interface Topology {

    /**
     * Adds a single node to the topology.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the specific topology implementation.
     *
     * @return the created node
     */
    @NonNull
    Node addNode();

    /**
     * Adds multiple nodes to the topology.
     *
     * <p>How the node is connected to existing nodes and its latency, jitter, and bandwidth depend on the specific topology implementation.
     *
     * @param count the number of nodes to add
     * @return list of created nodes
     */
    @NonNull
    List<Node> addNodes(int count);

    /**
     * Add an instrumented node to the topology.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @return the added instrumented node
     */
    @NonNull
    InstrumentedNode addInstrumentedNode();

    /**
     * Get the list of nodes in the topology.
     *
     * <p>The {@link List} cannot be modified directly. However, if a node is added or removed from the topology, the
     * list is automatically updated. That means, if it is necessary to have a constant list, it is recommended to
     * create a copy.
     *
     * @return a list of nodes in the topology
     */
    @NonNull
    List<Node> getNodes();
}
