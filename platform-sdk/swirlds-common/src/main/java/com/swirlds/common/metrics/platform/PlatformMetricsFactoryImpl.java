// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.PlatformMetricsFactory;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleAccumulator;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metric;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * An implementation of {@link PlatformMetricsFactory} that creates platform-internal {@link Metric}-instances
 */
public class PlatformMetricsFactoryImpl implements PlatformMetricsFactory {
    private final MetricsConfig metricsConfig;

    /**
     * Constructs a new PlatformMetricsFactoryImpl with the given configuration.
     * @param metricsConfig the configuration for this metrics factory
     */
    public PlatformMetricsFactoryImpl(@NonNull final MetricsConfig metricsConfig) {
        this.metricsConfig = Objects.requireNonNull(metricsConfig, "metricsConfig is null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Counter createCounter(final Counter.Config config) {
        return new PlatformCounter(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DoubleAccumulator createDoubleAccumulator(final DoubleAccumulator.Config config) {
        return new PlatformDoubleAccumulator(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DoubleGauge createDoubleGauge(final DoubleGauge.Config config) {
        return new PlatformDoubleGauge(config);
    }

    @Override
    public DurationGauge createDurationGauge(final DurationGauge.Config config) {
        return new PlatformDurationGauge(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> FunctionGauge<T> createFunctionGauge(final FunctionGauge.Config<T> config) {
        return new PlatformFunctionGauge<>(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IntegerAccumulator createIntegerAccumulator(final IntegerAccumulator.Config config) {
        return new PlatformIntegerAccumulator(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IntegerGauge createIntegerGauge(final IntegerGauge.Config config) {
        return new PlatformIntegerGauge(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> IntegerPairAccumulator<T> createIntegerPairAccumulator(final IntegerPairAccumulator.Config<T> config) {
        return new PlatformIntegerPairAccumulator<>(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LongAccumulator createLongAccumulator(final LongAccumulator.Config config) {
        return new PlatformLongAccumulator(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LongGauge createLongGauge(final LongGauge.Config config) {
        return new PlatformLongGauge(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RunningAverageMetric createRunningAverageMetric(final RunningAverageMetric.Config config) {
        if (config.isUseDefaultHalfLife()) {
            return new PlatformRunningAverageMetric(config.withHalfLife(metricsConfig.halfLife()));
        }
        return new PlatformRunningAverageMetric(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SpeedometerMetric createSpeedometerMetric(final SpeedometerMetric.Config config) {
        if (config.isUseDefaultHalfLife()) {
            return new PlatformSpeedometerMetric(config.withHalfLife(metricsConfig.halfLife()));
        }
        return new PlatformSpeedometerMetric(config);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("removal")
    @Override
    public StatEntry createStatEntry(final StatEntry.Config<?> config) {
        return new PlatformStatEntry(config);
    }
}
