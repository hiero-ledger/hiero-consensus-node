// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Implementation of {@link MultipleNodeLogResults} that stores the log results for multiple nodes.
 *
 * @param results the list of log results for individual nodes
 */
public record MultipleNodeLogResultsImpl(@NonNull List<SingleNodeLogResult> results) implements MultipleNodeLogResults {
    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults suppressingNode(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        final List<SingleNodeLogResult> filteredResults = results.stream()
                .filter(res -> Objects.equals(res.nodeId(), nodeId))
                .toList();

        return new MultipleNodeLogResultsImpl(filteredResults);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeLogResults suppressingLogMarker(@NonNull final LogMarker marker) {
        Objects.requireNonNull(marker, "marker cannot be null");
        final List<SingleNodeLogResult> filteredResults =
                results.stream().map(res -> res.suppressingLogMarker(marker)).toList();

        return new MultipleNodeLogResultsImpl(filteredResults);
    }
}
