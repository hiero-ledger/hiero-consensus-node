// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the event streams results of a group of nodes that were created during a test.
 */
public interface MultipleNodeEventStreamResults {

    /**
     * Returns an immutable list of {@link SingleNodeEventStreamResult} for all nodes
     *
     * @return the list of results
     */
    @NonNull
    List<SingleNodeEventStreamResult> results();

    /**
     * Excludes the event stream results of a specific node from the results.
     *
     * @param nodeId the {@link NodeId} of the node which event stream results are to be excluded
     * @return a new instance of {@link MultipleNodeEventStreamResults} with the specified node's results excluded
     */
    @NonNull
    MultipleNodeEventStreamResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the event stream results of a specific node from the results.
     *
     * @param node the {@link Node} which event stream results are to be excluded
     * @return a new instance of {@link MultipleNodeEventStreamResults} with the specified node's results excluded
     */
    @NonNull
    default MultipleNodeEventStreamResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }

    /**
     * Excludes the event stream results of one or more nodes from the current results.
     *
     * @param nodes the nodes whose event stream results are to be excluded
     * @return a new instance of {@link MultipleNodeEventStreamResults} with the specified nodes' event stream results excluded
     */
    @NonNull
    MultipleNodeEventStreamResults suppressingNodes(@NonNull final Collection<Node> nodes);

    /**
     * Excludes the event stream results of one or more nodes from the current results.
     *
     * @param nodes the nodes whose event stream results are to be excluded
     * @return a new instance of {@link MultipleNodeEventStreamResults} with the specified nodes' event stream results excluded
     */
    @NonNull
    default MultipleNodeEventStreamResults suppressingNodes(@NonNull final Node... nodes) {
        return suppressingNodes(Arrays.asList(nodes));
    }
}
