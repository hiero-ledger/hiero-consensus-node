// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricUtils;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#COUNTER} that holds {@link Measurement} per label set,
 * containing non-decreasing {@code double} value.
 */
public final class DoubleCounter extends SettableMetric<DoubleSupplier, DoubleCounter.Measurement> {

    private DoubleCounter(Builder builder) {
        super(builder);
    }

    /**
     * Create a metric key for a {@link DoubleCounter} with the given name.<br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<DoubleCounter> key(@NonNull String name) {
        return MetricKey.of(name, DoubleCounter.class);
    }

    /**
     * Create a builder for a {@link DoubleCounter} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull MetricKey<DoubleCounter> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link DoubleCounter} with the given metric name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    @Override
    protected Measurement createMeasurement(@NonNull DoubleSupplier initializer) {
        return new Measurement(initializer);
    }

    @Override
    protected MeasurementSnapshot createMeasurementSnapshot(
            @NonNull Measurement measurement, @NonNull LabelValues labelValues) {
        return new DoubleMeasurementSnapshot(labelValues, measurement::get);
    }

    @Override
    protected void reset(Measurement measurement) {
        measurement.reset();
    }

    /**
     * Builder for {@link DoubleCounter}.
     * <p>
     * Default initial value is {@code 0.0}, that can be changed via {@link #setDefaultInitValue(double)}.
     */
    public static final class Builder extends SettableMetric.Builder<DoubleSupplier, Builder, DoubleCounter> {

        private Builder(@NonNull MetricKey<DoubleCounter> key) {
            super(MetricType.COUNTER, key, MetricUtils.DOUBLE_ZERO_INIT);
        }

        /**
         * Set the initial value for the gauge and any measurement within this metric.
         *
         * @param defaultInitValue the initial value for any measurement within this metric
         * @throws IllegalArgumentException if the given value is negative
         * @return this builder
         */
        @NonNull
        public Builder setDefaultInitValue(double defaultInitValue) {
            if (defaultInitValue < 0.0) {
                throw new IllegalArgumentException(
                        "Default initial value for counter must be non-negative, but was: " + defaultInitValue);
            }
            return setDefaultInitializer(MetricUtils.asSupplier(defaultInitValue));
        }

        /**
         * Build the {@link DoubleCounter} metric.
         *
         * @return this builder
         */
        @NonNull
        @Override
        protected DoubleCounter buildMetric() {
            return new DoubleCounter(this);
        }
    }

    /**
     * The measurement data holding a non-decreasing {@code double} value.
     * Operations are thread-safe and atomic.
     */
    public static final class Measurement {

        private final DoubleSupplier initializer;
        private final DoubleAdder container = new DoubleAdder();

        private Measurement(@NonNull DoubleSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            increment(initializer.getAsDouble());
        }

        /**
         * Increment the counter by the given non-negative value.
         *
         * @param value the value to increment by, must be non-negative
         * @throws IllegalArgumentException if the given value is negative
         */
        public void increment(double value) {
            if (value < 0.0) {
                throw new IllegalArgumentException("Increment value must be non-negative, but was: " + value);
            }
            if (value != 0.0) {
                container.add(value);
            }
        }

        /**
         * Increment the counter by {@code 1.0}.
         */
        public void increment() {
            container.add(1.0);
        }

        double get() {
            return container.sum();
        }

        void reset() {
            container.reset();
            increment(initializer.getAsDouble());
        }
    }
}
