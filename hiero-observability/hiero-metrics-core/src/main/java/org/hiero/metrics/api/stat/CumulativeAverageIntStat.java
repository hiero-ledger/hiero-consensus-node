// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.stat;

import static org.hiero.metrics.api.stat.StatUtils.INT_AVERAGE;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import org.hiero.metrics.api.GaugeAdapter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.api.stat.container.AtomicIntPair;

public final class CumulativeAverageIntStat implements DoubleSupplier {

    private final IntSupplier initializer;
    private final AtomicIntPair container = AtomicIntPair.createAccumulatingSum();

    public CumulativeAverageIntStat() {
        this(StatUtils.INT_INIT);
    }

    public CumulativeAverageIntStat(IntSupplier initializer) {
        this.initializer = Objects.requireNonNull(initializer, "Initializer must not be null");
        update(initializer.getAsInt());
    }

    public static MetricKey<GaugeAdapter<IntSupplier, CumulativeAverageIntStat>> key(String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    public static GaugeAdapter.Builder<IntSupplier, CumulativeAverageIntStat> metricBuilder(
            MetricKey<GaugeAdapter<IntSupplier, CumulativeAverageIntStat>> key) {
        return metricBuilder(key, StatUtils.INT_INIT);
    }

    public static GaugeAdapter.Builder<IntSupplier, CumulativeAverageIntStat> metricBuilder(
            MetricKey<GaugeAdapter<IntSupplier, CumulativeAverageIntStat>> key, IntSupplier initializer) {
        return GaugeAdapter.builder(
                        key,
                        initializer,
                        CumulativeAverageIntStat::new,
                        new ToNumberFunction<>(CumulativeAverageIntStat::getAndReset))
                .withReset(CumulativeAverageIntStat::reset);
    }

    public void update(int value) {
        container.accumulate(value, 1);
    }

    public void reset() {
        int init = initializer.getAsInt();
        if (init == 0) {
            container.reset();
        } else {
            container.set(init, 1);
        }
    }

    @Override
    public double getAsDouble() {
        return container.computeDouble(INT_AVERAGE);
    }

    public double getAndReset() {
        int init = initializer.getAsInt();
        if (init == 0) {
            return container.computeDoubleAndReset(INT_AVERAGE);
        } else {
            return container.computeDoubleAndSet(INT_AVERAGE, init, 1);
        }
    }
}
