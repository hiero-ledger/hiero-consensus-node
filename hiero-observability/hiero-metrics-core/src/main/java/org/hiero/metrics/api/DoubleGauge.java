// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.hiero.metrics.api.stat.StatUtils.DOUBLE_INIT;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.internal.DoubleGaugeImpl;
import org.hiero.metrics.internal.measurement.AtomicDoubleGaugeMeasurement;
import org.hiero.metrics.internal.measurement.DoubleAccumulatorGaugeMeasurement;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link DoubleGaugeMeasurement} per label set.
 * <p>
 * The gauge could be configured to hold the last value set, or to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface DoubleGauge extends SettableMetric<DoubleSupplier, DoubleGaugeMeasurement> {

    /**
     * Create a metric key for a {@link DoubleGauge} with the given name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<DoubleGauge> key(@NonNull String name) {
        return MetricKey.of(name, DoubleGauge.class);
    }

    /**
     * Create a builder for a {@link DoubleGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<DoubleGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link DoubleGauge} with the given metric name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Builder for {@link DoubleGauge} using {@link DoubleGaugeMeasurement} per label set.
     * <p>
     * By default, it will export last value set, but could be configured to export accumulated values
     * using {@link #withOperator(DoubleBinaryOperator, boolean)}. <br>
     * Default initial value is {@code 0.0}, but could be modified with {@link #withInitValue(double)}.
     */
    final class Builder extends SettableMetric.Builder<DoubleSupplier, DoubleGaugeMeasurement, Builder, DoubleGauge> {

        private DoubleBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<DoubleGauge> key) {
            super(MetricType.GAUGE, key, DOUBLE_INIT, AtomicDoubleGaugeMeasurement::new);
        }

        /**
         * @return <code>true</code> if the gauge is reset to its initial value after each export,
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
        public Builder withInitValue(double initValue) {
            return withDefaultInitializer(StatUtils.asInitializer(initValue));
        }

        /**
         * Set the aggregation operator to use when updating the gauge value (applied to previous and new value).
         * If not set, the gauge will simply hold the last value set.
         *
         * @param operator      the aggregation operator, must not be {@code null}
         * @param resetOnExport if true, the gauge will be reset to its initial value after each export
         * @return this builder
         */
        @NonNull
        public Builder withOperator(DoubleBinaryOperator operator, boolean resetOnExport) {
            this.operator = Objects.requireNonNull(operator, "Operator must not be null");
            this.resetOnExport = resetOnExport;
            return this;
        }

        /**
         * Set the aggregation operator to track {@code max} spikes of the values.
         * Default initial value for newly created measurement is {@link Double#NEGATIVE_INFINITY}.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder withTrackingMaxSpike() {
            return withOperator(StatUtils.DOUBLE_MAX, true).withInitValue(Double.NEGATIVE_INFINITY);
        }

        /**
         * Set the aggregation operator to track {@code min} spikes of the values.
         * Default initial value for newly created measurement is {@link Double#POSITIVE_INFINITY}.
         * The gauge will be reset to its initial value after each export.
         *
         * @return this builder
         */
        public Builder withTrackingMinSpike() {
            return withOperator(StatUtils.DOUBLE_MIN, true).withInitValue(Double.POSITIVE_INFINITY);
        }

        /**
         * Build the {@link DoubleGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public DoubleGauge buildMetric() {
            if (operator != null) {
                withContainerFactory(init -> new DoubleAccumulatorGaugeMeasurement(operator, init));
            } else {
                withContainerFactory(AtomicDoubleGaugeMeasurement::new);
            }

            return new DoubleGaugeImpl(this);
        }
    }
}
