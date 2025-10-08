// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

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
     * @param results the list of {@link SingleNodeEventStreamResult} for all nodes
     */
    public MultipleNodeEventStreamResultsImpl(@NonNull final List<SingleNodeEventStreamResult> results) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("At least one result must be provided");
        }
        this.results = unmodifiableList(requireNonNull(results));
    }

    @NonNull
    @Override
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
