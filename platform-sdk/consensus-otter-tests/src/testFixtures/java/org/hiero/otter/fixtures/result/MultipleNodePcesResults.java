// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the results related to PCES files.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
@SuppressWarnings("unused")
public interface MultipleNodePcesResults {

    /**
     * Returns the list of {@link SingleNodePcesResult} for all nodes
     *
     * @return the list of results for all nodes
     */
    @NonNull
    List<SingleNodePcesResult> pcesResults();

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param nodeId the {@link NodeId} of the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodePcesResults} with the specified node's result excluded
     */
    @NonNull
    MultipleNodePcesResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param node the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodePcesResults} with the specified node's result excluded
     */
    @NonNull
    default MultipleNodePcesResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }

    /**
     * Excludes the PCES results of one or more nodes from the current results.
     *
     * @param nodes the nodes whose PCES results are to be excluded
     * @return a new instance of {@link MultipleNodePcesResults} with the specified nodes' PCES results excluded
     */
    @NonNull
    MultipleNodePcesResults suppressingNodes(@NonNull final Collection<Node> nodes);

    /**
     * Excludes the PCES results of one or more nodes from the current results.
     *
     * @param nodes the nodes whose PCES results are to be excluded
     * @return a new instance of {@link MultipleNodePcesResults} with the specified nodes' PCES results excluded
     */
    @NonNull
    default MultipleNodePcesResults suppressingNodes(@NonNull final Node... nodes) {
        return suppressingNodes(Arrays.asList(nodes));
    }
}
