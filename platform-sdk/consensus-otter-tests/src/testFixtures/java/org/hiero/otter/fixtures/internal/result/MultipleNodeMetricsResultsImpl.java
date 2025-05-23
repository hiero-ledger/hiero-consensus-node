// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.MultipleNodeMetricsResults;
import org.hiero.otter.fixtures.result.SingleNodeMetricsResult;

/**
 * Implementation of {@link MultipleNodeMetricsResults} that stores the metric results for multiple nodes.
 *
 * @param results the list of metric results for individual nodes
 */
public record MultipleNodeMetricsResultsImpl(@NonNull List<SingleNodeMetricsResult> results)
        implements MultipleNodeMetricsResults {
    /**
     * Constructor
     *
     * @param results the list of metric results for individual nodes
     */
    public MultipleNodeMetricsResultsImpl {
        Objects.requireNonNull(results);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MultipleNodeMetricsResults ignoring(@NonNull final Node node) {
        final List<SingleNodeMetricsResult> results = results().stream()
                .filter(snr -> !Objects.equals(snr.nodeId(), node.getSelfId()))
                .toList();
        return new MultipleNodeMetricsResultsImpl(results);
    }
}
