// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeMetricsResult;
import org.hiero.otter.fixtures.turtle.metric.NumberStats;

/**
 * Implementation of {@link SingleNodeMetricsResult} that stores the metric results for a single node.
 *
 * @param nodeId the ID of the node
 * @param identifier identifier of the metric
 * @param stats statistics of the metric
 */
public record SingleNodeMetricsResultImpl(
        @NonNull NodeId nodeId, @NonNull String identifier, @NonNull NumberStats stats)
        implements SingleNodeMetricsResult {

    /**
     * Constructor
     *
     * @param nodeId the ID of the node
     * @param identifier identifier of the metric
     * @param stats statistics of the metric
     */
    public SingleNodeMetricsResultImpl {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(identifier, "identifier cannot be null");
        Objects.requireNonNull(stats, "history cannot be null");
    }
}
