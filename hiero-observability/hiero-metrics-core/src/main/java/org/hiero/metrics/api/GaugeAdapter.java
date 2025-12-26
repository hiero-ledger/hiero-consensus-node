// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.internal.DoubleGaugeAdapterImpl;
import org.hiero.metrics.internal.LongGaugeAdapterImpl;

/**
 * A metric of type {@link MetricType#GAUGE} that holds custom measurement data (provided by the client code)
 * per label set. It allows to adapt any external class holding single numerical value to a gauge metric.
 * For multiple numerical values {@link StatsGaugeAdapter} can be used.
 * <p>
 * This metric can be used for cases when some custom logic is required to handle observed values.
 * It is responsibility of the client to ensure that external measurement is thread safe and provides atomic updates,
 * if needed.
 *
 * @param <M> the type of the measurement data used to hold the gauge value and provide method for observations
 *           and numerical value state access
 */
public interface GaugeAdapter<M> extends SettableMetric<Supplier<M>, M> {

    /**
     * Create a metric key for a {@link GaugeAdapter} with the given name.<br>
     * See {@link org.hiero.metrics.api.core.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @param <M>  the type of the measurement data
     * @return the metric key
     */
    @NonNull
    static <M> MetricKey<GaugeAdapter<M>> key(@NonNull String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric key.
     * The measurement will be created using the given factory.
     *
     * @param key              the metric key
     * @param measurementFactory the factory function to create the measurement
     * @param exportGetter     the function to get the numerical value from the measurement for export
     * @param <M>              the type of the measurement data
     * @return the builder
     */
    @NonNull
    static <M> Builder<M> builder(
            @NonNull MetricKey<GaugeAdapter<M>> key,
            @NonNull Supplier<M> measurementFactory,
            @NonNull ToNumberFunction<M> exportGetter) {
        return new Builder<>(key, measurementFactory, exportGetter);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric name.
     * The measurement will be created using the given factory.<br>
     * See {@link org.hiero.metrics.api.core.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name             the metric name
     * @param measurementFactory the factory function to create the measurement
     * @param exportGetter     the function to get the numerical value from the measurement for export
     * @param <M>              the type of the measurement data
     * @return the builder
     */
    static <M> Builder<M> builder(
            @NonNull String name, @NonNull Supplier<M> measurementFactory, @NonNull ToNumberFunction<M> exportGetter) {
        return builder(key(name), measurementFactory, exportGetter);
    }

    /**
     * Builder for {@link GaugeAdapter}.
     *
     * @param <M> the type of the measurement data held by the metric
     */
    final class Builder<M> extends SettableMetric.Builder<Supplier<M>, M, Builder<M>, GaugeAdapter<M>> {

        private final ToNumberFunction<M> exportGetter;
        private Consumer<M> reset;

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
                @NonNull ToNumberFunction<M> exportGetter) {
            super(MetricType.GAUGE, key, measurementFactory, Supplier::get);
            this.exportGetter = Objects.requireNonNull(exportGetter, "exportGetter cannot be null");
        }

        /**
         * Get the function holder to convert the value to {@code double} or {@code long} for export.
         *
         * @return the value converter function
         */
        @NonNull
        public ToNumberFunction<M> getExportGetter() {
            return exportGetter;
        }

        /**
         * Get the optional reset function to reset the measurement.
         *
         * @return the reset function, or {@code null} if not set
         */
        @Nullable
        public Consumer<M> getReset() {
            return reset;
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
            if (exportGetter.isFloatingPointFunction()) {
                return new DoubleGaugeAdapterImpl<>(this);
            } else {
                return new LongGaugeAdapterImpl<>(this);
            }
        }
    }
}
