// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.internal.DoubleGaugeAdapterImpl;
import org.hiero.metrics.internal.LongGaugeAdapterImpl;

/**
 * A stateful metric of type {@link MetricType#GAUGE} that holds custom data point (provided by the client code)
 * per label set. It allows to adapt any external class holding single numerical value to a gauge metric.
 * For multiple numerical values {@link StatsGaugeAdapter} can be used.
 * <p>
 * This metric can be used for cases when some custom logic is required to handle observed values,
 * which cannot be achieved with accumulating {@link LongGauge} or {@link DoubleGauge} or with {@link GenericGauge}.<br>
 * If aggregation can be archived using accumulating operations, then use {@link LongGauge} or {@link DoubleGauge}.<br>
 * If latest set custom value type has to be reported, then {@link GenericGauge} should be used.
 * <p>
 * It is responsibility of the client to ensure that external data point is thread safe and provides atomic updates,
 * if needed.
 *
 * @param <D> the type of the data point used to hold the gauge value and provide method for observations
 *           and numerical value state access
 */
public interface GaugeAdapter<D> extends StatefulMetric<Supplier<D>, D> {

    /**
     * Create a metric key for a {@link GaugeAdapter} with the given name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @param <D>  the type of the data point
     * @return the metric key
     */
    @NonNull
    static <D> MetricKey<GaugeAdapter<D>> key(@NonNull String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric key.
     * The data point will be created using the given factory.
     *
     * @param key              the metric key
     * @param dataPointFactory the factory function to create the data point
     * @param exportGetter     the function to get the numerical value from the data point for export
     * @param <D>              the type of the data point
     * @return the builder
     */
    @NonNull
    static <D> Builder<D> builder(
            @NonNull MetricKey<GaugeAdapter<D>> key,
            @NonNull Supplier<D> dataPointFactory,
            @NonNull ToNumberFunction<D> exportGetter) {
        return new Builder<>(key, dataPointFactory, exportGetter);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric name.
     * The data point will be created using the given factory.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name             the metric name
     * @param dataPointFactory the factory function to create the data point
     * @param exportGetter     the function to get the numerical value from the data point for export
     * @param <D>              the type of the data point
     * @return the builder
     */
    static <D> Builder<D> builder(
            @NonNull String name, @NonNull Supplier<D> dataPointFactory, @NonNull ToNumberFunction<D> exportGetter) {
        return builder(key(name), dataPointFactory, exportGetter);
    }

    /**
     * Builder for {@link GaugeAdapter}.
     */
    final class Builder<D> extends StatefulMetric.Builder<Supplier<D>, D, Builder<D>, GaugeAdapter<D>> {

        private final ToNumberFunction<D> exportGetter;
        private Consumer<D> reset;

        /**
         * Create a builder for a {@link GaugeAdapter} with the given metric key.
         *
         * @param key                the metric key
         * @param dataPointFactory   the factory function to create the data point
         * @param exportGetter     the function to get the {@code double} value from the data point for export
         */
        private Builder(
                @NonNull MetricKey<GaugeAdapter<D>> key,
                @NonNull Supplier<D> dataPointFactory,
                @NonNull ToNumberFunction<D> exportGetter) {
            super(MetricType.GAUGE, key, dataPointFactory, Supplier::get);
            this.exportGetter = Objects.requireNonNull(exportGetter, "exportGetter cannot be null");
        }

        /**
         * Get the function holder to convert the value to {@code double} or {@code long} for export.
         *
         * @return the value converter function
         */
        @NonNull
        public ToNumberFunction<D> getExportGetter() {
            return exportGetter;
        }

        /**
         * Get the optional reset function to reset the data point.
         *
         * @return the reset function, or {@code null} if not set
         */
        @Nullable
        public Consumer<D> getReset() {
            return reset;
        }

        /**
         * Set the optional reset function to reset the data points.
         * If set, this function will be called to reset the data points when needed.
         *
         * @param reset the reset function, must not be {@code null}
         * @return this builder
         */
        @NonNull
        public Builder<D> withReset(@NonNull Consumer<D> reset) {
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
        protected GaugeAdapter<D> buildMetric() {
            if (exportGetter.isFloatingPointFunction()) {
                return new DoubleGaugeAdapterImpl<>(this);
            } else {
                return new LongGaugeAdapterImpl<>(this);
            }
        }
    }
}
