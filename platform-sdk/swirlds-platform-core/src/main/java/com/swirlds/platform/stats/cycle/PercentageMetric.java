// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.cycle;

import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A metric that is used to output a percentage 0-100%
 */
public class PercentageMetric {
    private static final String APPENDIX = " (%)";
    private static final int PERCENT = 100;

    private final IntegerPairAccumulator<Double> container;

    /**
     * @throws NullPointerException in case {@code name} parameter is {@code null}
     */
    protected PercentageMetric(
            final Metrics metrics,
            final String category,
            final String name,
            final String description,
            final BiFunction<Integer, Integer, Double> resultFunction) {

        Objects.requireNonNull(name, "name must not be null");
        container = metrics.getOrCreate(
                new IntegerPairAccumulator.Config<>(category, name + APPENDIX, Double.class, resultFunction)
                        .withDescription(description)
                        .withFormat(FloatFormats.FORMAT_3_1));
    }

    protected PercentageMetric(
            final Metrics metrics, final String category, final String name, final String description) {

        this(metrics, category, name, description, PercentageMetric::calculatePercentage);
    }

    /**
     * Atomically update the left and right value
     *
     * @param leftValue the left value
     * @param rightValue the right value
     */
    protected void update(final int leftValue, final int rightValue) {
        container.update(leftValue, rightValue);
    }

    public static double calculatePercentage(final int total, final int part) {
        if (total == 0) {
            return 0;
        }
        return (((double) part) / total) * PERCENT;
    }

    /**
     * Returns the current value
     *
     * @return the current value
     */
    public double get() {
        return container.get();
    }
}
