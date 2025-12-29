// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.MetricUtils;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;
import org.hiero.metrics.internal.LongGaugeImpl;
import org.hiero.metrics.internal.measurement.AtomicLongGaugeMeasurement;
import org.hiero.metrics.internal.measurement.LongAccumulatorGaugeMeasurement;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link LongGaugeMeasurement} per label set.
 * <p>
 * The gauge could be configured to hold the last value set, or to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface LongGauge extends SettableMetric<LongSupplier, LongGaugeMeasurement> {

    /**
     * Create a metric key for a {@link LongGauge} with the given name. <br>
     * See {@link org.hiero.metrics.api.core.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
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
     * See {@link org.hiero.metrics.api.core.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * A builder for a {@link LongGauge} using {@link LongGaugeMeasurement} per label set.
     * <p>
     * By default, it will export last value set, but could be configured to export accumulated values
     * using {@link #setOperator(LongBinaryOperator, boolean)}. <br>
     * Default initial value is <code>0L</code>.
     */
    final class Builder extends SettableMetric.Builder<LongSupplier, LongGaugeMeasurement, Builder, LongGauge> {

        private LongBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<LongGauge> key) {
            super(MetricType.GAUGE, key, MetricUtils.LONG_ZERO_INIT, AtomicLongGaugeMeasurement::new);
        }

        /**
         * @return {@code true} if the gauge is reset to its initial value after each export, {@code false} otherwise
         */
        public boolean isResetOnExport() {
            return resetOnExport;
        }

        /**
         * Set the initial value for the gauge and any measurement within this metric.
         *
         * @param initValue the initial value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setInitValue(long initValue) {
            return setDefaultInitializer(MetricUtils.asInitializer(initValue));
        }

        /**
         * Set the aggregation operator to {@code sum}.
         * Default initial value for newly created measurement is {@code 0L}.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder setSumOperator() {
            return setOperator(Long::sum, true).setInitValue(0L);
        }

        /**
         * Set the aggregation operator to {@code max}.
         * Default initial value for newly created measurement is {@link Long#MIN_VALUE}.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder setMaxOperator() {
            return setOperator(Long::max, true).setInitValue(Long.MIN_VALUE);
        }

        /**
         * Set the aggregation operator to {@code min}.
         * Default initial value for newly created measurement is {@link Long#MAX_VALUE}.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder setMinOperator() {
            return setOperator(Long::min, true).setInitValue(Long.MAX_VALUE);
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
        public Builder setOperator(@NonNull LongBinaryOperator operator, boolean resetOnExport) {
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
                setMeasurementFactory(init -> new LongAccumulatorGaugeMeasurement(operator, init));
            } else {
                setMeasurementFactory(AtomicLongGaugeMeasurement::new);
            }

            return new LongGaugeImpl(this);
        }
    }
}
