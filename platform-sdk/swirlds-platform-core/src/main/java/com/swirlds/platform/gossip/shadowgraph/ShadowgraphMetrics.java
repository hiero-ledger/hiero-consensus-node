// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_5_3;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.stats.AverageStat;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Encapsulates metrics for the shadowgraph.
 */
public class ShadowgraphMetrics {

    private final AverageStat indicatorsWaitingForExpiry;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public ShadowgraphMetrics(@NonNull final PlatformContext platformContext) {
        indicatorsWaitingForExpiry = new AverageStat(
                platformContext.getMetrics(),
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
