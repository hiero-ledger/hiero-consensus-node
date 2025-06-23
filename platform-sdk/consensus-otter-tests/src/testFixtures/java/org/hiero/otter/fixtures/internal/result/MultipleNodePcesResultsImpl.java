// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.MultipleNodePcesResults;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;

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
    public MultipleNodePcesResults ignoring(@NonNull final Node node) {
        final List<SingleNodePcesResult> filtered = pcesResults.stream()
                .filter(it -> Objects.equals(it.nodeId(), node.getSelfId()))
                .toList();
        return new MultipleNodePcesResultsImpl(filtered);
    }
}
