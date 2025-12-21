// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.platform;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.impl.DefaultMetricsFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.metrics.DurationGauge;
import org.hiero.consensus.metrics.FunctionGauge;
import org.hiero.consensus.metrics.IntegerPairAccumulator;
import org.hiero.consensus.metrics.PlatformMetricsFactory;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.metrics.SpeedometerMetric;
import org.hiero.consensus.metrics.StatEntry;
import org.hiero.consensus.metrics.config.MetricsConfig;

/**
 * An implementation of {@link PlatformMetricsFactory} that creates platform-internal {@link Metric}-instances
 */
public class PlatformMetricsFactoryImpl extends DefaultMetricsFactory implements PlatformMetricsFactory {

    private final MetricsConfig metricsConfig;

    /**
     * Constructs a new PlatformMetricsFactoryImpl with the given configuration.
     * @param metricsConfig the configuration for this metrics factory
     */
    public PlatformMetricsFactoryImpl(@NonNull final MetricsConfig metricsConfig) {
        this.metricsConfig = Objects.requireNonNull(metricsConfig, "metricsConfig is null");
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
    public <T> IntegerPairAccumulator<T> createIntegerPairAccumulator(final IntegerPairAccumulator.Config<T> config) {
        return new PlatformIntegerPairAccumulator<>(config);
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
