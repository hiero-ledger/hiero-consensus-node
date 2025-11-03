// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics.event;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;

/**
 * A metric that tracks the maximum duration between a given start time and the current time. This metric is reset
 * whenever a snapshot is taken, so it tracks the maximum duration over each snapshot interval.
 */
public class MaxDurationMetric {
    private final Time time;
    private final LongAccumulator metric;

    /**
     * Constructor.
     *
     * @param metrics the metrics system
     * @param time    the time source
     * @param name    the name of the metric
     */
    public MaxDurationMetric(@NonNull final Metrics metrics, @NonNull final Time time, @NonNull final String name) {
        final LongAccumulator.Config config = new LongAccumulator.Config(PLATFORM_CATEGORY, name)
                .withUnit("ms")
                .withAccumulator(Math::max)
                .withInitialValue(0);
        metric = metrics.getOrCreate(config);
        this.time = time;
    }

    /**
     * Update the metric with the duration between the given start time and the current time.
     *
     * @param start the start time
     */
    public void update(@NonNull final Instant start) {
        final long duration = Duration.between(start, time.now()).toMillis();
        metric.update(duration);
    }
}
