// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricUtils;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link Measurement} per label set,
 * accumulating {@code long} value using an operator (e.g. min, max).
 */
public final class LongAccumulatorGauge extends SettableMetric<LongSupplier, LongAccumulatorGauge.Measurement> {

    private final LongBinaryOperator operator;
    private final boolean resetOnExport;

    private LongAccumulatorGauge(Builder builder) {
        super(builder);

        operator = builder.operator;
        resetOnExport = builder.resetOnExport;
    }

    /**
     * Create a metric key for a {@link LongAccumulatorGauge} with the given name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<LongAccumulatorGauge> key(@NonNull String name) {
        return MetricKey.of(name, LongAccumulatorGauge.class);
    }

    /**
     * Create a builder for a {@link LongAccumulatorGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull MetricKey<LongAccumulatorGauge> key, @NonNull LongBinaryOperator operator) {
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
    public static Builder minBuilder(@NonNull MetricKey<LongAccumulatorGauge> key) {
        return new Builder(key, Long::min).setDefaultInitValue(Long.MAX_VALUE);
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
        return new Builder(key, Long::max).setDefaultInitValue(Long.MIN_VALUE);
    }

    @Override
    protected Measurement createMeasurement(@NonNull LongSupplier initializer) {
        return new Measurement(operator, initializer);
    }

    @Override
    protected MeasurementSnapshot createMeasurementSnapshot(
            @NonNull Measurement measurement, @NonNull LabelValues labelValues) {
        return new LongMeasurementSnapshot(labelValues, resetOnExport ? measurement::getAndReset : measurement::get);
    }

    @Override
    protected void reset(Measurement measurement) {
        measurement.reset();
    }

    /**
     * Builder for {@link LongAccumulatorGauge}.
     * <p>
     * Default initial value is {@code 0.0}, that can be changed via {@link #setDefaultInitValue(long)}.
     */
    public static final class Builder extends SettableMetric.Builder<LongSupplier, Builder, LongAccumulatorGauge> {

        private final LongBinaryOperator operator;
        private boolean resetOnExport = false;

        private Builder(@NonNull MetricKey<LongAccumulatorGauge> key, @NonNull LongBinaryOperator operator) {
            super(MetricType.GAUGE, key, MetricUtils.LONG_ZERO_INIT);
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
         * @param defaultInitValue the initial value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setDefaultInitValue(long defaultInitValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(defaultInitValue));
        }

        /**
         * Build the {@link LongAccumulatorGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected LongAccumulatorGauge buildMetric() {
            return new LongAccumulatorGauge(this);
        }
    }

    /**
     * The measurement data holding an accumulated {@code long} value.
     * Operations are thread-safe and atomic.
     */
    public static final class Measurement {

        private final LongAccumulator accumulator;

        private Measurement(@NonNull LongBinaryOperator operator, @NonNull LongSupplier initializer) {
            Objects.requireNonNull(operator, "operator must not be null");
            Objects.requireNonNull(initializer, "initializer must not be null");

            accumulator = new LongAccumulator(operator, initializer.getAsLong());
        }

        /**
         * Accumulate the given value using the specified operator.
         *
         * @param value the value to accumulate
         */
        public void accumulate(long value) {
            accumulator.accumulate(value);
        }

        long get() {
            return accumulator.get();
        }

        long getAndReset() {
            return accumulator.getThenReset();
        }

        void reset() {
            accumulator.reset();
        }
    }
}
