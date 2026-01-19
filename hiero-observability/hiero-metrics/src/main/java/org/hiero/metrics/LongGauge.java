// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricUtils;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link Measurement} per label set, containing latest set {@code long} value.
 */
public final class LongGauge extends SettableMetric<LongSupplier, LongGauge.Measurement> {

    private LongGauge(Builder builder) {
        super(builder);
    }

    /**
     * Create a metric key for a {@link LongGauge} with the given name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<LongGauge> key(@NonNull String name) {
        return MetricKey.of(name, LongGauge.class);
    }

    /**
     * Create a builder for a {@link LongGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull MetricKey<LongGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link LongGauge} with the given metric name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the metric name
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
     * A builder for a {@link LongGauge}.
     * <p>
     * Default initial value is {@code 0L}, that can be changed via {@link #setDefaultInitValue(long)}.
     */
    public static final class Builder extends SettableMetric.Builder<LongSupplier, Builder, LongGauge> {

        private Builder(@NonNull MetricKey<LongGauge> key) {
            super(MetricType.GAUGE, key, MetricUtils.LONG_ZERO_INIT);
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
         * Build the {@link LongGauge} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected LongGauge buildMetric() {
            return new LongGauge(this);
        }
    }

    /**
     * A measurement holding the latest set {@code long} value.
     * Operations are thread-safe and atomic.
     */
    public static final class Measurement {

        private final LongSupplier initializer;
        private final AtomicLong container;

        private Measurement(@NonNull LongSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            container = new AtomicLong(initializer.getAsLong());
        }

        /**
         * Set the value of this measurement.
         *
         * @param value the value to set
         */
        public void set(long value) {
            container.set(value);
        }

        long get() {
            return container.get();
        }

        void reset() {
            container.set(initializer.getAsLong());
        }
    }
}
