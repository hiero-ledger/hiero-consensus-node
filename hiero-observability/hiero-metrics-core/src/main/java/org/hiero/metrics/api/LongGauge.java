// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.datapoint.LongGaugeDataPoint;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.internal.LongGaugeImpl;
import org.hiero.metrics.internal.datapoint.AtomicLongGaugeDataPoint;
import org.hiero.metrics.internal.datapoint.LongAccumulatorGaugeDataPoint;

/**
 * A stateful metric of type {@link MetricType#GAUGE} that holds {@link LongGaugeDataPoint} per label set.
 * <p>
 * The gauge could be configured to hold the last value set, or to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface LongGauge extends StatefulMetric<LongSupplier, LongGaugeDataPoint> {

    /**
     * Create a metric key for a {@link LongGauge} with the given name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<LongGauge> key(@NonNull String name) {
        return MetricKey.of(name, LongGauge.class);
    }

    /**
     * Create a builder for a {@link LongGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<LongGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link LongGauge} with the given metric name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * A builder for a {@link LongGauge} using {@link LongGaugeDataPoint} per label set.
     * <p>
     * By default, it will export last value set, but could be configured to export accumulated values
     * using {@link #withOperator(LongBinaryOperator, boolean)}. <br>
     * Default initial value is <code>0L</code>.
     */
    final class Builder extends StatefulMetric.Builder<LongSupplier, LongGaugeDataPoint, Builder, LongGauge> {

        private LongBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<LongGauge> key) {
            super(MetricType.GAUGE, key, StatUtils.LONG_INIT, AtomicLongGaugeDataPoint::new);
        }

        /**
         * @return {@code true} if the gauge is reset to its initial value after each export, {@code false} otherwise
         */
        public boolean isResetOnExport() {
            return resetOnExport;
        }

        /**
         * Set the initial value for the gauge and any data point within this metric.
         *
         * @param initValue the initial value for any data point within this metric
         * @return this builder
         */
        @NonNull
        public Builder withInitValue(long initValue) {
            return withDefaultInitializer(StatUtils.asInitializer(initValue));
        }

        /**
         * Set the aggregation operator to {@code max} and initial value to {@link Long#MIN_VALUE},
         * which won't be exported if not observed at least once.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder withTrackingMaxSpike() {
            return withOperator(StatUtils.LONG_MAX, true).withInitValue(Long.MIN_VALUE);
        }

        /**
         * Set the aggregation operator to {@code min} and initial value to {@link Long#MAX_VALUE},
         * which won't be exported if not observed at least once.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder withTrackingMinSpike() {
            return withOperator(StatUtils.LONG_MIN, true).withInitValue(Long.MAX_VALUE);
        }

        /**
         * Set the aggregation operator to use when updating the gauge value.
         * If not set, the gauge will simply hold the last value set.
         *
         * @param operator the aggregation operator, must not be {@code null}
         * @param resetOnExport if true, the gauge will be reset to its initial value after each export
         * @return this builder
         */
        @NonNull
        public Builder withOperator(@NonNull LongBinaryOperator operator, boolean resetOnExport) {
            this.operator = Objects.requireNonNull(operator, "Operator must not be null");
            this.resetOnExport = resetOnExport;
            return this;
        }

        /**
         * Build the {@link LongGauge} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public LongGauge buildMetric() {
            if (operator != null) {
                withContainerFactory(init -> new LongAccumulatorGaugeDataPoint(operator, init));
            } else {
                withContainerFactory(AtomicLongGaugeDataPoint::new);
            }

            return new LongGaugeImpl(this);
        }
    }
}
