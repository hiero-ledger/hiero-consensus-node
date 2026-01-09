// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.LongAccumulatorGaugeMeasurement;
import org.hiero.metrics.internal.LongAccumulatorGaugeImpl;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link LongAccumulatorGaugeMeasurement} per label set.
 * <p>
 * The gauge could be configured to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface LongAccumulatorGauge extends SettableMetric<LongSupplier, LongAccumulatorGaugeMeasurement> {

    /**
     * Create a metric key for a {@link LongAccumulatorGauge} with the given name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<LongAccumulatorGauge> key(@NonNull String name) {
        return MetricKey.of(name, LongAccumulatorGauge.class);
    }

    /**
     * Create a builder for a {@link LongAccumulatorGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<LongAccumulatorGauge> key, @NonNull LongBinaryOperator operator) {
        return new Builder(key, operator);
    }

    /**
     * Create a builder for a {@link LongAccumulatorGauge} with the given metric key for accumulating minimum {@code long} value.
     * Default initial value is set to {@code Long.MAX_VALUE}.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder minBuilder(@NonNull MetricKey<LongAccumulatorGauge> key) {
        return new Builder(key, Long::min).setInitValue(Long.MAX_VALUE);
    }

    /**
     * Create a builder for a {@link LongAccumulatorGauge} with the given metric key for accumulating maximum {@code double} value.
     * Default initial value is set to {@code Long.MIN_VALUE}.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder maxBuilder(@NonNull MetricKey<LongAccumulatorGauge> key) {
        return new Builder(key, Long::max).setInitValue(Long.MIN_VALUE);
    }

    /**
     * Builder for {@link LongAccumulatorGauge} using {@link LongAccumulatorGaugeMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0.0}, but could be modified with {@link #setInitValue(long)}.
     */
    final class Builder extends SettableMetric.Builder<LongSupplier, Builder, LongAccumulatorGauge> {

        private final LongBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<LongAccumulatorGauge> key, @NonNull LongBinaryOperator operator) {
            super(MetricType.GAUGE, key, MetricUtils.LONG_ZERO_INIT);
            this.operator = Objects.requireNonNull(operator, "operator must not be null");
        }

        @NonNull
        public LongBinaryOperator getOperator() {
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
        public Builder setInitValue(long initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link LongAccumulatorGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public LongAccumulatorGauge buildMetric() {
            return new LongAccumulatorGaugeImpl(this);
        }
    }
}
