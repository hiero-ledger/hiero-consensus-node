// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.DoubleAccumulatorGaugeMeasurement;
import org.hiero.metrics.internal.DoubleAccumulatorGaugeImpl;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link DoubleAccumulatorGaugeMeasurement} per label set.
 * <p>
 * The gauge could be configured to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface DoubleAccumulatorGauge extends SettableMetric<DoubleSupplier, DoubleAccumulatorGaugeMeasurement> {

    /**
     * Create a metric key for a {@link DoubleAccumulatorGauge} with the given name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<DoubleAccumulatorGauge> key(@NonNull String name) {
        return MetricKey.of(name, DoubleAccumulatorGauge.class);
    }

    /**
     * Create a builder for a {@link DoubleAccumulatorGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<DoubleAccumulatorGauge> key, @NonNull DoubleBinaryOperator operator) {
        return new Builder(key, operator);
    }

    /**
     * Create a builder for a {@link DoubleAccumulatorGauge} with the given metric key for accumulating minimum {@code double} value.
     * Default initial value is set to {@code Double.POSITIVE_INFINITY}.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder minBuilder(@NonNull MetricKey<DoubleAccumulatorGauge> key) {
        return new Builder(key, Double::min).setInitValue(Double.POSITIVE_INFINITY);
    }

    /**
     * Create a builder for a {@link DoubleAccumulatorGauge} with the given metric key for accumulating maximum {@code double} value.
     * Default initial value is set to {@code Double.NEGATIVE_INFINITY}.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder maxBuilder(@NonNull MetricKey<DoubleAccumulatorGauge> key) {
        return new Builder(key, Double::max).setInitValue(Double.NEGATIVE_INFINITY);
    }

    /**
     * Builder for {@link DoubleAccumulatorGauge} using {@link DoubleAccumulatorGaugeMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0.0}, but could be modified with {@link #setInitValue(double)}.
     */
    final class Builder extends SettableMetric.Builder<DoubleSupplier, Builder, DoubleAccumulatorGauge> {

        private final DoubleBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<DoubleAccumulatorGauge> key, @NonNull DoubleBinaryOperator operator) {
            super(MetricType.GAUGE, key, MetricUtils.DOUBLE_ZERO_INIT);
            this.operator = Objects.requireNonNull(operator, "operator must not be null");
        }

        @NonNull
        public DoubleBinaryOperator getOperator() {
            return operator;
        }

        /**
         * Configure the gauge to be reset to its initial value after each export.
         *
         * @return this builder
         */
        @NonNull
        public Builder resetOnExport() {
            this.resetOnExport = true;
            return this;
        }

        /**
         * @return {@code true} if the gauge is reset to its initial value after each export,
         * {@code false} otherwise.
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
        public Builder setInitValue(double initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link DoubleAccumulatorGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public DoubleAccumulatorGauge buildMetric() {
            return new DoubleAccumulatorGaugeImpl(this);
        }
    }
}
