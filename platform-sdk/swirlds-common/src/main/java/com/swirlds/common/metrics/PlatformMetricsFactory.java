// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.MetricsFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

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

    /**
     * Creates a {@link Metric}.
     * <p>
     * The default implementation calls the appropriate method within this factory.
     *
     * @param config
     * 		the configuration
     * @return the new {@code Metric}
     * @param <T> sub-interface of the generated {@code Metric}
     */
    default <T extends Metric> T createMetric(@NonNull final MetricConfig<T, ?> config) {
        Objects.requireNonNull(config, "config");

        // We use the double-dispatch pattern to create a Metric. This simplifies the API, because it allows us
        // to have a single method for all types of metrics. (The alternative would have been a method like
        // getOrCreateCounter() for each type of metric.)
        //
        // This method here call MetricConfig.create() providing the MetricsFactory. This method is overridden by
        // each subclass of MetricConfig to call the specific method in MetricsFactory, i.e. Counter.Config
        // calls MetricsFactory.createCounter().

        return config.create(this);
    }
}
