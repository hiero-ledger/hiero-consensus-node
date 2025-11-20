// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import static org.hiero.metrics.api.stat.StatUtils.INT_AVERAGE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.api.stat.container.AtomicIntPair;

/**
 * A statistic that atomically computes the cumulative average of integer values
 * using {@link AtomicIntPair} as the underlying container.
 */
public final class CumulativeAverageIntStat implements DoubleSupplier {

    private final AtomicIntPair container = AtomicIntPair.createAccumulatingSum();

    /**
     * Creates a {@link MetricKey} for a {@link GaugeAdapter} that holds a {@link CumulativeAverageIntStat}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<GaugeAdapter<CumulativeAverageIntStat>> key(@NonNull String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    /**
     * Creates a {@link GaugeAdapter.Builder} for a {@link CumulativeAverageIntStat}.
     * Metric will reset the cumulative average after each export.
     *
     * @param key         the metric key
     * @return the metric builder
     */
    @NonNull
    public static GaugeAdapter.Builder<CumulativeAverageIntStat> metricBuilder(
            @NonNull MetricKey<GaugeAdapter<CumulativeAverageIntStat>> key) {
        return GaugeAdapter.builder(
                        key,
                        CumulativeAverageIntStat::new,
                        new ToNumberFunction<>(CumulativeAverageIntStat::getAndReset))
                .withReset(CumulativeAverageIntStat::reset);
    }

    /**
     * Creates a {@link GaugeAdapter.Builder} for a {@link CumulativeAverageIntStat}.
     * Metric will reset the cumulative average after each export.
     *
     * @param name the name of the metric
     * @return the metric builder
     */
    @NonNull
    public static GaugeAdapter.Builder<CumulativeAverageIntStat> metricBuilder(@NonNull String name) {
        return metricBuilder(key(name));
    }

    /**
     * Update the cumulative average with the provided integer value.
     *
     * @param value the integer value to update the average with
     */
    public void update(int value) {
        container.accumulate(value, 1);
    }

    /**
     * Reset the cumulative average to the initial value provided by the initializer.
     */
    public void reset() {
        container.reset();
    }

    @Override
    public double getAsDouble() {
        return container.computeDouble(INT_AVERAGE);
    }

    /**
     * Get the current cumulative average and reset it to the initial value provided by the initializer.
     *
     * @return the current cumulative average before reset
     */
    public double getAndReset() {
        return container.computeDoubleAndReset(INT_AVERAGE);
    }
}
