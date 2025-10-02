// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_6;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Bundles a triplet of metrics: a counter, an accumulator and an average of a given value.
 */
public record CountAccumulateAverageMetricTriplet(
        Counter counter, LongAccumulator accumulator, RunningAverageMetric average) {
    private static final String AVERAGE_SUFFIX = "_avg";
    private static final String COUNT_SUFFIX = "_count";
    private static final String ACCUMULATOR_SUFFIX = "_sum";

    public static CountAccumulateAverageMetricTriplet create(
            @NonNull final Metrics metrics,
            @NonNull final String category,
            @NonNull final String baseName,
            @NonNull final String baseDescription) {
        final var averageConfig = new RunningAverageMetric.Config(category, baseName + AVERAGE_SUFFIX)
                .withDescription(baseDescription + " (running average)")
                .withFormat(FORMAT_10_6);

        final var counterConfig = new Counter.Config(category, baseName + COUNT_SUFFIX)
                .withDescription(baseDescription + " (observation count)");

        final var accumulatorConfig = new LongAccumulator.Config(category, baseName + ACCUMULATOR_SUFFIX)
                .withDescription(baseDescription + " (sum)")
                .withAccumulator(Long::sum);

        return new CountAccumulateAverageMetricTriplet(
                metrics.getOrCreate(counterConfig),
                metrics.getOrCreate(accumulatorConfig),
                metrics.getOrCreate(averageConfig));
    }

    /**
     * Records an observed value:
     *  - increases the accumulator by `value`
     *  - increases the counter by 1
     *  - updates the running average accordingly
     *
     * @param value the value to record
     */
    public void recordObservation(final long value) {
        counter.increment();
        accumulator.update(value);
        average.update(value);
    }
}
