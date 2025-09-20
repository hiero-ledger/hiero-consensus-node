// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricsFactory;

/**
 * Factory for all {@link Metric}-implementations
 */
public interface PlatformMetricsFactory extends MetricsFactory {

    /**
     * Creates a {@link DurationGauge}
     *
     * @param config the configuration
     * @return the new {@link DurationGauge}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    DurationGauge createDurationGauge(final DurationGauge.Config config);

    /**
     * Creates a {@link FunctionGauge}
     *
     * @param config the configuration
     * @param <T>    the type of the value that will be contained in the {@code FunctionGauge}
     * @return the new {@code FunctionGauge}
     * @throws IllegalArgumentException if {@code config} is {@code null}
     */
    <T> FunctionGauge<T> createFunctionGauge(final FunctionGauge.Config<T> config);

    /**
     * Creates a {@link IntegerPairAccumulator}
     *
     * @param config
     * 		the configuration
     * @return the new {@code IntegerPairAccumulator}
     * @throws IllegalArgumentException
     * 		if {@code config} is {@code null}
     */
    <T> IntegerPairAccumulator<T> createIntegerPairAccumulator(IntegerPairAccumulator.Config<T> config);

    /**
     * Creates a {@link RunningAverageMetric}
     *
     * @param config
     * 		the configuration
     * @return the new {@code RunningAverageMetric}
     * @throws IllegalArgumentException
     * 		if {@code config} is {@code null}
     */
    RunningAverageMetric createRunningAverageMetric(final RunningAverageMetric.Config config);

    /**
     * Creates a {@link SpeedometerMetric}
     *
     * @param config
     * 		the configuration
     * @return the new {@code SpeedometerMetric}
     * @throws IllegalArgumentException
     * 		if {@code config} is {@code null}
     */
    SpeedometerMetric createSpeedometerMetric(final SpeedometerMetric.Config config);

    /**
     * Creates a {@link StatEntry}
     *
     * @param config
     * 		the configuration
     * @return the new {@code StatEntry}
     * @throws IllegalArgumentException
     * 		if {@code config} is {@code null}
     */
    @SuppressWarnings("removal")
    StatEntry createStatEntry(final StatEntry.Config<?> config);
}
