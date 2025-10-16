// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.MultipleNodeEventStreamResults;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;

/**
 * Default implementation of {@link MultipleNodeEventStreamResults}
 */
public class MultipleNodeEventStreamResultsImpl implements MultipleNodeEventStreamResults {

    private final List<SingleNodeEventStreamResult> results;

    /**
     * Constructor for {@link MultipleNodeEventStreamResultsImpl}.
     *
     * @param nodes the list of nodes for which to capture event stream results
     */
    public MultipleNodeEventStreamResultsImpl(@NonNull final Collection<Node> nodes) {
        this(nodes.stream().map(Node::newEventStreamResult).toList());
    }

    /**
     * Constructor for {@link MultipleNodeEventStreamResultsImpl}.
     *
     * @param results the list of {@link SingleNodeEventStreamResult} for all nodes
     */
    public MultipleNodeEventStreamResultsImpl(@NonNull final List<SingleNodeEventStreamResult> results) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("At least one result must be provided");
        }
        if (results.stream().allMatch(SingleNodeEventStreamResult::hasReconnected)) {
            throw new IllegalArgumentException("At least one result must be from a node that has not reconnected");
        }
        this.results = results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SingleNodeEventStreamResult> results() {
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeEventStreamResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodeEventStreamResult> filtered = results.stream()
                .filter(result -> !Objects.equals(nodeId, result.nodeId()))
                .toList();
        return new MultipleNodeEventStreamResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeEventStreamResults suppressingNodes(@NonNull final Collection<Node> nodes) {
        final Set<NodeId> nodeIdsToSuppress = nodes.stream().map(Node::selfId).collect(Collectors.toSet());
        final List<SingleNodeEventStreamResult> filtered = results.stream()
                .filter(node -> !nodeIdsToSuppress.contains(node.nodeId()))
                .toList();
        return new MultipleNodeEventStreamResultsImpl(filtered);
    }
}
