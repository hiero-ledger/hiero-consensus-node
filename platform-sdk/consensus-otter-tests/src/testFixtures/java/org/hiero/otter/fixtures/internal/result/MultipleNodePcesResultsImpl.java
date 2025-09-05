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
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.jetbrains.annotations.NotNull;

/**
 * Default implementation of {@link MultipleNodePcesResults}
 *
 * @param pcesResults the list of {@link SingleNodePcesResult}
 */
public record MultipleNodePcesResultsImpl(@NonNull List<SingleNodePcesResult> pcesResults)
        implements MultipleNodePcesResults {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePcesResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodePcesResult> filtered = pcesResults.stream()
                .filter(it -> Objects.equals(it.nodeId(), nodeId))
                .toList();
        return new MultipleNodePcesResultsImpl(filtered);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull MultipleNodePcesResults suppressingNodes(@NotNull final Collection<Node> nodes) {
        final Set<NodeId> nodeIdsToSuppress = nodes.stream().map(Node::selfId).collect(Collectors.toSet());
        final List<SingleNodePcesResult> filtered = pcesResults.stream()
                .filter(result -> !nodeIdsToSuppress.contains(result.nodeId()))
                .toList();
        return new MultipleNodePcesResultsImpl(filtered);
    }
}
