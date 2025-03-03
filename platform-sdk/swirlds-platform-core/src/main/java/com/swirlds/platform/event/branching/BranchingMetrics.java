// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.branching;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Encapsulates metrics for branching events.
 */
public class BranchingMetrics {

    private static final SpeedometerMetric.Config BRANCHING_EVENT_SPEEDOMETER_CONFIG =
            new SpeedometerMetric.Config("platform", "branchingEvents").withUnit("hz");
    private final SpeedometerMetric branchingEvents;

    private static final LongGauge.Config BRANCHING_NODE_COUNT_CONFIG =
            new LongGauge.Config("platform", "branchingNodeCount").withUnit("count");
    private final LongGauge branchingNodeCount;

    private static final DoubleGauge.Config BRANCHING_WEIGHT_FRACTION_CONFIG =
            new DoubleGauge.Config("platform", "branchingWeightFraction").withUnit("fraction");
    private final DoubleGauge branchingWeightFraction;

    /**
     * Constructor.
     *
     * @param platformContext the platform context
     */
    public BranchingMetrics(@NonNull final PlatformContext platformContext) {
        branchingEvents = platformContext.getMetrics().getOrCreate(BRANCHING_EVENT_SPEEDOMETER_CONFIG);
        branchingNodeCount = platformContext.getMetrics().getOrCreate(BRANCHING_NODE_COUNT_CONFIG);
        branchingWeightFraction = platformContext.getMetrics().getOrCreate(BRANCHING_WEIGHT_FRACTION_CONFIG);
    }

    /**
     * Report that a branching event has been detected.
     */
    public void reportBranchingEvent() {
        branchingEvents.cycle();
    }

    /**
     * Report the number nodes with a non-ancient branching event.
     *
     * @param count the number of nodes
     */
    public void reportBranchingNodeCount(final int count) {
        branchingNodeCount.set(count);
    }

    /**
     * Report the fraction of nodes (by weight) with a non-ancient branching event.
     *
     * @param fraction the fraction
     */
    public void reportBranchingWeightFraction(final double fraction) {
        branchingWeightFraction.set(fraction);
    }
}
