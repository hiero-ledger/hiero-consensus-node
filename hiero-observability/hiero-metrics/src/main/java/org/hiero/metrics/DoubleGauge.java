// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.MetricUtils;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link Measurement} per label set, containing last set {@code double} value.
 */
public final class DoubleGauge extends SettableMetric<DoubleSupplier, DoubleGauge.Measurement> {

    private DoubleGauge(Builder builder) {
        super(builder);
    }

    /**
     * Create a metric key for a {@link DoubleGauge} with the given name. <br>
     * Name must match {@value MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<DoubleGauge> key(@NonNull String name) {
        return MetricKey.of(name, DoubleGauge.class);
    }

    /**
     * Create a builder for a {@link DoubleGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull MetricKey<DoubleGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link DoubleGauge} with the given metric name.<br>
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
     * Builder for {@link DoubleGauge}.
     * <p>
     * Default initial value is {@code 0.0}, that can be changed via {@link #setDefaultInitValue(double)}.
     */
    public static final class Builder extends SettableMetric.Builder<DoubleSupplier, Builder, DoubleGauge> {

        private Builder(@NonNull MetricKey<DoubleGauge> key) {
            super(MetricType.GAUGE, key, MetricUtils.DOUBLE_ZERO_INIT);
        }

        /**
         * Set the initial value for the gauge and any measurement within this metric.
         *
         * @param defaultInitValue the initial value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setDefaultInitValue(double defaultInitValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(defaultInitValue));
        }

        /**
         * Build the {@link DoubleGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected DoubleGauge buildMetric() {
            return new DoubleGauge(this);
        }
    }

    /**
     * The measurement data holding last set {@code double} value.
     * Operations are thread-safe and atomic.
     */
    public static final class Measurement {

        private final DoubleSupplier initializer;
        private final AtomicLong container;

        private Measurement(@NonNull DoubleSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            container = new AtomicLong();
            reset();
        }

        /**
         * Set the value of this measurement.
         *
         * @param value the value to set
         */
        public void set(double value) {
            container.set(Double.doubleToRawLongBits(value));
        }

        double get() {
            return Double.longBitsToDouble(container.get());
        }

        void reset() {
            set(initializer.getAsDouble());
        }
    }
}
