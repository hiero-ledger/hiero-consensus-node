// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricUtils;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#COUNTER} that holds {@link Measurement} per label set,
 * containing non-decreasing {@code long} value.
 */
public final class LongCounter extends SettableMetric<LongSupplier, LongCounter.Measurement> {

    private LongCounter(Builder builder) {
        super(builder);
    }

    /**
     * Create a metric key for a {@link LongCounter} with the given name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<LongCounter> key(@NonNull String name) {
        return MetricKey.of(name, LongCounter.class);
    }

    /**
     * Create a builder for a {@link LongCounter} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull MetricKey<LongCounter> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link LongCounter} with the given metric name. <br>
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
    protected Measurement createMeasurement(@NonNull LongSupplier initializer) {
        return new Measurement(initializer);
    }

    @Override
    protected MeasurementSnapshot createMeasurementSnapshot(
            @NonNull Measurement measurement, @NonNull LabelValues labelValues) {
        return new LongMeasurementSnapshot(labelValues, measurement::get);
    }

    @Override
    protected void reset(Measurement measurement) {
        measurement.reset();
    }

    /**
     * Builder for {@link LongCounter}.
     * <p>
     * Default initial value is {@code 0L}, that can be changed via {@link #setDefaultInitValue(long)}.
     */
    public static final class Builder extends SettableMetric.Builder<LongSupplier, Builder, LongCounter> {

        private Builder(@NonNull MetricKey<LongCounter> key) {
            super(MetricType.COUNTER, key, MetricUtils.LONG_ZERO_INIT);
        }

        /**
         * Sets the default initial value for the counter measurements created by this metric.
         *
         * @param defaultInitValue the initial value
         * @throws IllegalArgumentException if the given value is negative
         * @return this builder
         */
        @NonNull
        public Builder setDefaultInitValue(long defaultInitValue) {
            if (defaultInitValue < 0L) {
                throw new IllegalArgumentException(
                        "Default initial value for counter must be non-negative, but was: " + defaultInitValue);
            }
            return setDefaultInitializer(MetricUtils.asSupplier(defaultInitValue));
        }

        /**
         * Build the {@link LongCounter} metric.
         *
         * @return this builder
         */
        @NonNull
        @Override
        protected LongCounter buildMetric() {
            return new LongCounter(this);
        }
    }

    /**
     * A measurement holding a non-decreasing {@code long} value.
     * Operations are thread-safe and atomic.
     */
    public static final class Measurement {

        private final LongSupplier initializer;
        private final LongAdder container = new LongAdder();

        private Measurement(@NonNull LongSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            reset();
        }

        /**
         * Increments the counter by the given non-negative value.
         *
         * @param value the value to increment by
         * @throws IllegalArgumentException if the given value is negative
         */
        public void increment(long value) {
            if (value < 0L) {
                throw new IllegalArgumentException("Increment value must be non-negative, but was: " + value);
            }
            if (value != 0L) {
                container.add(value);
            }
        }

        /**
         * Increments the counter by {@code 1}.
         */
        public void increment() {
            container.add(1L);
        }

        long get() {
            return container.sum();
        }

        void reset() {
            container.reset();
            increment(initializer.getAsLong());
        }
    }
}
