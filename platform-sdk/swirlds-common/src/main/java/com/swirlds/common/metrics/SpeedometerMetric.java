// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

/**
 * This class measures how many times per second the cycle() method is called. It is recalculated every
 * period, where its period is 0.1 seconds by default. If instantiated with gamma=0.9, then half the
 * weighting comes from the last 7 periods. If 0.99 it's 70 periods, 0.999 is 700, etc.
 * <p>
 * The timer starts at instantiation, and can be reset with the reset() method.
 */
public interface SpeedometerMetric extends Metric {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default MetricType getMetricType() {
        return MetricType.SPEEDOMETER;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default DataType getDataType() {
        return DataType.FLOAT;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    default EnumSet<ValueType> getValueTypes() {
        return EnumSet.of(VALUE, MAX, MIN, STD_DEV);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    Double get(@NonNull final ValueType valueType);

    /**
     * Getter of the {@code halfLife}
     *
     * @return the {@code halfLife}
     */
    double getHalfLife();

    /**
     * calling update(N) is equivalent to calling cycle() N times almost simultaneously. Calling cycle() is
     * equivalent to calling update(1). Calling update(0) will update the estimate of the cycles per second
     * by looking at the current time (to update the seconds) without incrementing the count of the cycles.
     * So calling update(0) repeatedly with no calls to cycle() will cause the cycles per second estimate to
     * go asymptotic to zero.
     * <p>
     * The speedometer initially keeps a simple, uniformly-weighted average of the number of calls to
     * cycle() per second since the start of the run. Over time, that makes each new call to cycle() have
     * less weight (because there are more of them). Eventually, the weight of a new call drops below the
     * weight it would have under the exponentially-weighted average. At that point, it switches to the
     * exponentially-weighted average.
     *
     * @param value
     * 		number of cycles to record
     */
    void update(final double value);

    /**
     * This is the method to call repeatedly. The average calls per second will be calculated.
     */
    void cycle();

    /**
     * Get the average number of times per second the cycle() method was called. This is an
     * exponentially-weighted average of recent timings.
     *
     * @return the estimated number of calls to cycle() per second, recently
     */
    double get();

    /**
     * Configuration of a {@link SpeedometerMetric}
     */
    final class Config extends PlatformMetricConfig<SpeedometerMetric, Config> {

        private final double halfLife;
        private final boolean useDefaultHalfLife;

        /**
         * Constructor of {@code SpeedometerMetric.Config}
         *
         * The {@code useDefaultHalfLife} determines whether the default {@code halfLife} value
         * (see {@link MetricsConfig#halfLife()}) should be used during the creation of a metric based on
         * this configuration. If set to {@code false}, the specific {@code halfLife} defined in this configuration will
         * be used instead.
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @throws IllegalArgumentException
         * 		if one of the parameters is {@code null} or consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name, FloatFormats.FORMAT_11_3);
            this.halfLife = -1;
            this.useDefaultHalfLife = true;
        }

        private Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final String description,
                @NonNull final String unit,
                @NonNull final String format,
                final double halfLife,
                final boolean useDefaultHalfLife) {
            super(category, name, description, unit, format);
            this.halfLife = halfLife;
            this.useDefaultHalfLife = useDefaultHalfLife;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public SpeedometerMetric.Config withDescription(@NonNull final String description) {
            return new SpeedometerMetric.Config(
                    getCategory(),
                    getName(),
                    description,
                    getUnit(),
                    getFormat(),
                    getHalfLife(),
                    isUseDefaultHalfLife());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public SpeedometerMetric.Config withUnit(@NonNull final String unit) {
            return new SpeedometerMetric.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    unit,
                    getFormat(),
                    getHalfLife(),
                    isUseDefaultHalfLife());
        }

        /**
         * Sets the {@link Metric#getFormat() Metric.format} in fluent style.
         *
         * @param format
         * 		the format-string
         * @return a new configuration-object with updated {@code format}
         * @throws IllegalArgumentException
         * 		if {@code format} is {@code null} or consists only of whitespaces
         */
        @NonNull
        public SpeedometerMetric.Config withFormat(@NonNull final String format) {
            return new SpeedometerMetric.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    getUnit(),
                    format,
                    getHalfLife(),
                    isUseDefaultHalfLife());
        }

        /**
         * Getter of the {@code halfLife}.
         *
         * @return the {@code halfLife}
         */
        public double getHalfLife() {
            return halfLife;
        }

        /**
         * Getter of the {@code useDefaultHalfLife}.
         *
         * @return the {@code useDefaultHalfLife}
         */
        public boolean isUseDefaultHalfLife() {
            return useDefaultHalfLife;
        }

        /**
         * Fluent-style setter of the {@code halfLife}.
         *
         * @param halfLife
         * 		the {@code halfLife}
         * @return a reference to {@code this}
         */
        @NonNull
        public SpeedometerMetric.Config withHalfLife(final double halfLife) {
            return new SpeedometerMetric.Config(
                    getCategory(), getName(), getDescription(), getUnit(), getFormat(), halfLife, false);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<SpeedometerMetric> getResultClass() {
            return SpeedometerMetric.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public SpeedometerMetric create(@NonNull final PlatformMetricsFactory factory) {
            return factory.createSpeedometerMetric(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .appendSuper(super.toString())
                    .append("halfLife", halfLife)
                    .toString();
        }
    }
}
