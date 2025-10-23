// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the results related to marker files.
 */
@SuppressWarnings("unused")
public interface MultipleNodeMarkerFileResults {

    /**
     * Returns the list of {@link SingleNodeMarkerFileResult} for all nodes
     *
     * @return the list of results for all nodes
     */
    @NonNull
    List<SingleNodeMarkerFileResult> results();

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param nodeId the {@link NodeId} of the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodeMarkerFileResults} with the specified node's result excluded
     */
    @NonNull
    MultipleNodeMarkerFileResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the results of a specific node from the current results.
     *
     * @param node the node whose result is to be excluded
     * @return a new instance of {@link MultipleNodeMarkerFileResults} with the specified node's result excluded
     */
    @NonNull
    default MultipleNodeMarkerFileResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }

    /**
     * Excludes the marker file results of one or more nodes from the current results.
     *
     * @param nodes the nodes whose marker file results are to be excluded
     * @return a new instance of {@link MultipleNodeMarkerFileResults} with the specified nodes' marker file results excluded
     */
    @NonNull
    MultipleNodeMarkerFileResults suppressingNodes(@NonNull final Collection<Node> nodes);

    /**
     * Excludes the marker file results of one or more nodes from the current results.
     *
     * @param nodes the nodes whose marker file results are to be excluded
     * @return a new instance of {@link MultipleNodeMarkerFileResults} with the specified nodes' marker file results excluded
     */
    @NonNull
    default MultipleNodeMarkerFileResults suppressingNodes(@NonNull final Node... nodes) {
        return suppressingNodes(Arrays.asList(nodes));
    }
    /**
     * Subscribes to marker file changes the nodes go through.
     *
     * <p>The subscriber will be notified every time a node writes a new marker file.
     *
     * @param subscriber the subscriber that will receive the marker file updates
     */
    void subscribe(MarkerFileSubscriber subscriber);
}
