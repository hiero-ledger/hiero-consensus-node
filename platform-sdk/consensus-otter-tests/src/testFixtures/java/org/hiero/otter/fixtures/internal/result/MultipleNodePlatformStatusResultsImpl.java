// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;

/**
 * Default implementation of {@link MultipleNodePlatformStatusResults}
 *
 * @param statusProgressions the list of {@link SingleNodePlatformStatusResults}
 */
public record MultipleNodePlatformStatusResultsImpl(@NonNull List<SingleNodePlatformStatusResults> statusProgressions)
        implements MultipleNodePlatformStatusResults {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodePlatformStatusResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodePlatformStatusResults> filtered = statusProgressions.stream()
                .filter(it -> Objects.equals(it.nodeId(), nodeId))
                .toList();
        return new MultipleNodePlatformStatusResultsImpl(filtered);
    }
}
