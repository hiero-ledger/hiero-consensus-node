// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

/**
 * This class maintains a running average of some numeric value. It is exponentially weighted in time, with
 * a given half life. If it is always given the same value, then that value will be the average, regardless
 * of the timing.
 */
public interface RunningAverageMetric extends Metric {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default MetricType getMetricType() {
        return MetricType.RUNNING_AVERAGE;
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
        return ALL_VALUE_TYPES_SET;
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
     * Incorporate "value" into the running average. If it is the same on every call, then the average will
     * equal it, no matter how those calls are timed. If it has various values on various calls, then the
     * running average will weight the more recent ones more heavily, with a half life of halfLife seconds,
     * where halfLife was passed in when this object was instantiated.
     * <p>
     * If this is called repeatedly with a value of X over a long period, then suddenly all calls start
     * having a value of Y, then after half-life seconds, the average will have moved halfway from X to Y,
     * regardless of how often update was called, as long as it is called at least once at the end of that
     * period.
     *
     * @param value
     * 		the value to incorporate into the running average
     */
    void update(final double value);

    /**
     * Get the average of recent calls to recordValue(). This is an exponentially-weighted average of recent
     * calls, with the weighting by time, not by number of calls to recordValue().
     *
     * @return the running average as of the last time recordValue was called
     */
    double get();

    /**
     * Configuration of a {@link RunningAverageMetric}
     */
    final class Config extends PlatformMetricConfig<RunningAverageMetric, Config> {

        private double halfLife;

        /**
         * Constructor of {@code RunningAverageMetric.Config}
         *
         * If no {@code halfLife} value specified, default value will be used during the creation of a metric based on
         * this {@link MetricsConfig#halfLife()}.
         *
         * @param category the kind of metric (stats are grouped or filtered by this)
         * @param name a short name for the statistic
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name);
            withFormat(FloatFormats.FORMAT_11_3);
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
         * Fluent-style setter of the {@code halfLife}.
         *
         * @param halfLife
         * 		the {@code halfLife}
         * @return a new configuration-object with updated {@code halfLife}
         */
        @NonNull
        public RunningAverageMetric.Config withHalfLife(final double halfLife) {
            if (halfLife <= 0) {
                throw new IllegalArgumentException("Half-life must be positive, but was: " + halfLife);
            }
            this.halfLife = halfLife;
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<RunningAverageMetric> getResultClass() {
            return RunningAverageMetric.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public RunningAverageMetric create(@NonNull final PlatformMetricsFactory factory) {
            return factory.createRunningAverageMetric(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Config self() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ToStringBuilder selfToString() {
            return super.selfToString().append("halfLife", halfLife);
        }
    }
}
