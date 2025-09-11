// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the status progression results of a group of nodes.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
@SuppressWarnings("unused")
public interface MultipleNodePlatformStatusResults extends OtterResult {

    /**
     * Returns the list of {@link SingleNodePlatformStatusResult} for all nodes
     *
     * @return the list of status progressions
     */
    @NonNull
    List<SingleNodePlatformStatusResult> results();

    /**
     * Excludes the status progression of a specific node from the current results.
     *
     * @param nodeId the {@link NodeId} of the node whose status progression is to be excluded
     * @return a new instance of {@link MultipleNodePlatformStatusResults} with the specified node's status progression
     * excluded
     */
    @NonNull
    MultipleNodePlatformStatusResults suppressingNode(@NonNull NodeId nodeId);

    /**
     * Excludes the status progression of a specific node from the current results.
     *
     * @param node the node whose status progression is to be excluded
     * @return a new instance of {@link MultipleNodePlatformStatusResults} with the specified node's status progression excluded
     */
    @NonNull
    default MultipleNodePlatformStatusResults suppressingNode(@NonNull final Node node) {
        return suppressingNode(node.selfId());
    }

    /**
     * Excludes the status progression of one or more nodes from the current results.
     *
     * @param nodes the nodes whose status progressions are to be excluded
     * @return a new instance of {@link MultipleNodePlatformStatusResults} with the specified nodes' status progressions excluded
     */
    @NonNull
    MultipleNodePlatformStatusResults suppressingNodes(@NonNull final Collection<Node> nodes);

    /**
     * Excludes the status progression of one or more nodes from the current results.
     *
     * @param nodes the nodes whose status progressions are to be excluded
     * @return a new instance of {@link MultipleNodePlatformStatusResults} with the specified nodes' status progressions excluded
     */
    @NonNull
    default MultipleNodePlatformStatusResults suppressingNodes(@NonNull final Node... nodes) {
        return suppressingNodes(Arrays.asList(nodes));
    }

    /**
     * Subscribes to {@link PlatformStatus} changes the nodes go through.
     *
     * <p>The subscriber will be notified every time the status of one of the nodes changes.
     *
     * @param subscriber the subscriber that will receive the new status
     */
    void subscribe(PlatformStatusSubscriber subscriber);
}
