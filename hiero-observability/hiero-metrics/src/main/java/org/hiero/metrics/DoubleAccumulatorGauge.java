// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricUtils;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link Measurement} per label set,
 * accumulating {@code double} value using an operator (e.g. min, max).
 */
public final class DoubleAccumulatorGauge extends SettableMetric<DoubleSupplier, DoubleAccumulatorGauge.Measurement> {

    private final DoubleBinaryOperator operator;
    private final boolean resetOnExport;

    private DoubleAccumulatorGauge(Builder builder) {
        super(builder);

        this.operator = builder.operator;
        this.resetOnExport = builder.resetOnExport;
    }

    /**
     * Create a metric key for a {@link DoubleAccumulatorGauge} with the given name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<DoubleAccumulatorGauge> key(@NonNull String name) {
        return MetricKey.of(name, DoubleAccumulatorGauge.class);
    }

    /**
     * Create a builder for a {@link DoubleAccumulatorGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(
            @NonNull MetricKey<DoubleAccumulatorGauge> key, @NonNull DoubleBinaryOperator operator) {
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
    public static Builder minBuilder(@NonNull MetricKey<DoubleAccumulatorGauge> key) {
        return new Builder(key, Double::min).setDefaultInitValue(Double.POSITIVE_INFINITY);
    }

    /**
     * Create a builder for a {@link DoubleAccumulatorGauge} with the given metric key for accumulating maximum {@code double} value.
     * Default initial value is set to {@code Double.NEGATIVE_INFINITY}.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder maxBuilder(@NonNull MetricKey<DoubleAccumulatorGauge> key) {
        return new Builder(key, Double::max).setDefaultInitValue(Double.NEGATIVE_INFINITY);
    }

    @Override
    protected Measurement createMeasurement(@NonNull DoubleSupplier initializer) {
        return new Measurement(operator, initializer);
    }

    @Override
    protected MeasurementSnapshot createMeasurementSnapshot(
            @NonNull Measurement measurement, @NonNull LabelValues labelValues) {
        return new DoubleMeasurementSnapshot(labelValues, resetOnExport ? measurement::getAndReset : measurement::get);
    }

    @Override
    protected void reset(Measurement measurement) {
        measurement.reset();
    }

    /**
     * Builder for {@link DoubleAccumulatorGauge}.
     * <p>
     * Default initial value is {@code 0.0}, that can be changed via {@link #setDefaultInitValue(double)}.
     */
    public static final class Builder extends SettableMetric.Builder<DoubleSupplier, Builder, DoubleAccumulatorGauge> {

        private final DoubleBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<DoubleAccumulatorGauge> key, @NonNull DoubleBinaryOperator operator) {
            super(MetricType.GAUGE, key, MetricUtils.DOUBLE_ZERO_INIT);
            this.operator = Objects.requireNonNull(operator, "operator must not be null");
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
         * Set the initial value for the gauge and any measurement within this metric.
         *
         * @param initValue the initial value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setDefaultInitValue(double initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link DoubleAccumulatorGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected DoubleAccumulatorGauge buildMetric() {
            return new DoubleAccumulatorGauge(this);
        }
    }

    /**
     * A measurement that accumulates {@code double} values using a specified operator.
     * Operations are thread-safe and atomic.
     */
    public static final class Measurement {

        private final DoubleAccumulator accumulator;

        private Measurement(@NonNull DoubleBinaryOperator operator, @NonNull DoubleSupplier initializer) {
            Objects.requireNonNull(operator, "operator must not be null");
            Objects.requireNonNull(initializer, "initializer must not be null");

            accumulator = new DoubleAccumulator(operator, initializer.getAsDouble());
        }

        /**
         * Accumulate the given value using the specified operator.
         *
         * @param value the value to accumulate
         */
        public void accumulate(double value) {
            accumulator.accumulate(value);
        }

        double get() {
            return accumulator.get();
        }

        double getAndReset() {
            return accumulator.getThenReset();
        }

        void reset() {
            accumulator.reset();
        }
    }
}
