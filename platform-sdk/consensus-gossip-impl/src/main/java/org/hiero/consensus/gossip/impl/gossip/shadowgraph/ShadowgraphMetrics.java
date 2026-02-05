// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_5_3;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.metrics.statistics.AverageStat;

/**
 * Encapsulates metrics for the shadowgraph.
 */
public class ShadowgraphMetrics {

    private final AverageStat indicatorsWaitingForExpiry;

    /**
     * Constructor
     *
     * @param metrics the metrics system
     */
    public ShadowgraphMetrics(@NonNull final Metrics metrics) {
        indicatorsWaitingForExpiry = new AverageStat(
                metrics,
                PLATFORM_CATEGORY,
                "indicatorsWaitingForExpiry",
                "the average number of indicators waiting to be expired by the shadowgraph",
                FORMAT_5_3,
                AverageStat.WEIGHT_VOLATILE);
    }

    /**
     * Called by {@link Shadowgraph} to update the number of generations that should be expired but can't be yet due to
     * reservations.
     *
     * @param numGenerations the new number of generations
     */
    public void updateIndicatorsWaitingForExpiry(final long numGenerations) {
        indicatorsWaitingForExpiry.update(numGenerations);
    }
}
