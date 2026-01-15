// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;
import org.hiero.metrics.core.SettableMetric;

/**
 * A metric of type {@link MetricType#GAUGE} that holds custom measurement data (provided by the client code)
 * per label set. It allows to adapt any external class holding single numerical value to a gauge metric.
 * <p>
 * This metric can be used for cases when some custom logic is required to handle observed values.
 * It is responsibility of the client to ensure that external measurement is thread safe and provides atomic updates,
 * if needed.
 *
 * @param <M> the type of the measurement data used to hold the gauge value and provide method for observations
 *           and numerical value state access
 */
public final class GaugeAdapter<M> extends SettableMetric<Supplier<M>, M> {

    private final ToLongFunction<M> exportGetterLong;
    private final ToDoubleFunction<M> exportGetterDouble;
    private final Consumer<M> reset;

    private GaugeAdapter(Builder<M> builder) {
        super(builder);

        exportGetterLong = builder.exportGetterLong;
        exportGetterDouble = builder.exportGetterDouble;
        reset = builder.reset != null ? builder.reset : container -> {};
    }

    /**
     * Create a metric key for a {@link GaugeAdapter} with the given name.<br>
     * Name must match {@value org.hiero.metrics.core.MetricUtils#METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @param <M>  the type of the measurement data
     * @return the metric key
     */
    @NonNull
    public static <M> MetricKey<GaugeAdapter<M>> key(@NonNull String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric key.
     * The measurement will be created using the given factory.
     *
     * @param key              the metric key
     * @param measurementFactory the factory function to create the measurement
     * @param exportGetter     the function to get the {@code long} value from the measurement for export
     * @param <M>              the type of the measurement data
     * @return the builder
     */
    @NonNull
    public static <M> Builder<M> builderForLong(
            @NonNull MetricKey<GaugeAdapter<M>> key,
            @NonNull Supplier<M> measurementFactory,
            @NonNull ToLongFunction<M> exportGetter) {
        return new Builder<>(key, measurementFactory, exportGetter);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric key.
     * The measurement will be created using the given factory.
     *
     * @param key              the metric key
     * @param measurementFactory the factory function to create the measurement
     * @param exportGetter     the function to get the {@code double} value from the measurement for export
     * @param <M>              the type of the measurement data
     * @return the builder
     */
    @NonNull
    public static <M> Builder<M> builderForDouble(
            @NonNull MetricKey<GaugeAdapter<M>> key,
            @NonNull Supplier<M> measurementFactory,
            @NonNull ToDoubleFunction<M> exportGetter) {
        return new Builder<>(key, measurementFactory, exportGetter);
    }

    @Override
    protected M createMeasurement(@NonNull Supplier<M> initializer) {
        return initializer.get();
    }

    @Override
    protected MeasurementSnapshot createMeasurementSnapshot(@NonNull M measurement, @NonNull LabelValues labelValues) {
        if (exportGetterLong != null) {
            return new LongMeasurementSnapshot(labelValues, () -> exportGetterLong.applyAsLong(measurement));
        } else {
            return new DoubleMeasurementSnapshot(labelValues, () -> exportGetterDouble.applyAsDouble(measurement));
        }
    }

    @Override
    protected void reset(M measurement) {
        reset.accept(measurement);
    }

    /**
     * Builder for {@link GaugeAdapter}.
     *
     * @param <M> the type of the measurement data held by the metric
     */
    public static final class Builder<M> extends SettableMetric.Builder<Supplier<M>, Builder<M>, GaugeAdapter<M>> {

        private final ToLongFunction<M> exportGetterLong;
        private final ToDoubleFunction<M> exportGetterDouble;
        private Consumer<M> reset;

        /**
         * Create a builder for a {@link GaugeAdapter} with the given metric key.
         *
         * @param key                the metric key
         * @param measurementFactory   the factory function to create the measurement
         * @param exportGetter     the function to get the {@code long} value from the measurement for export
         */
        private Builder(
                @NonNull MetricKey<GaugeAdapter<M>> key,
                @NonNull Supplier<M> measurementFactory,
                @NonNull ToLongFunction<M> exportGetter) {
            super(MetricType.GAUGE, key, measurementFactory);
            this.exportGetterLong = Objects.requireNonNull(exportGetter, "exportGetter cannot be null");
            exportGetterDouble = null;
        }

        /**
         * Create a builder for a {@link GaugeAdapter} with the given metric key.
         *
         * @param key                the metric key
         * @param measurementFactory   the factory function to create the measurement
         * @param exportGetter     the function to get the {@code double} value from the measurement for export
         */
        private Builder(
                @NonNull MetricKey<GaugeAdapter<M>> key,
                @NonNull Supplier<M> measurementFactory,
                @NonNull ToDoubleFunction<M> exportGetter) {
            super(MetricType.GAUGE, key, measurementFactory);
            this.exportGetterDouble = Objects.requireNonNull(exportGetter, "exportGetter cannot be null");
            exportGetterLong = null;
        }

        /**
         * Set the optional reset function to reset the measurements.
         * If set, this function will be called to reset the measurement when needed.
         *
         * @param reset the reset function, must not be {@code null}
         * @return this builder
         */
        @NonNull
        public Builder<M> setReset(@NonNull Consumer<M> reset) {
            this.reset = Objects.requireNonNull(reset, "Value reset must not be null");
            return this;
        }

        /**
         * Build the {@link GaugeAdapter} instance.
         *
         * @return the built {@link GaugeAdapter} instance
         */
        @NonNull
        @Override
        protected GaugeAdapter<M> buildMetric() {
            return new GaugeAdapter<>(this);
        }
    }
}
