// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.hiero.metrics.api.stat.StatUtils.NO_DEFAULT_INITIALIZER;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.internal.DefaultGaugeAdapter;

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
 * @param <I> the type of the initializer used to create the data point
 * @param <D> the type of the data point used to hold the gauge value and provide method for observations
 *           and numerical value state access
 */
public interface GaugeAdapter<I, D> extends StatefulMetric<I, D> {

    /**
     * Create a metric key for a {@link GaugeAdapter} with the given name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @param <I>  the type of the initializer used to create the data point
     * @param <D>  the type of the data point
     * @return the metric key
     */
    @NonNull
    static <I, D> MetricKey<GaugeAdapter<I, D>> key(@NonNull String name) {
        return MetricKey.of(name, GaugeAdapter.class);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric key.
     *
     * @param key                the metric key
     * @param defaultInitializer the default initializer used to create the data point
     * @param dataPointFactory   the factory function to create the data point using the initializer
     * @param exportGetter       the function to get the numerical value from the data point for export
     * @param <I>                the type of the initializer used to create the data point
     * @param <D>                the type of the data point
     * @return the builder
     */
    @NonNull
    static <I, D> Builder<I, D> builder(
            @NonNull MetricKey<GaugeAdapter<I, D>> key,
            @NonNull I defaultInitializer,
            @NonNull Function<I, D> dataPointFactory,
            @NonNull Function<D, Number> exportGetter) {
        return new Builder<>(key, defaultInitializer, dataPointFactory, exportGetter);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name               the metric name
     * @param defaultInitializer the default initializer used to create the data point
     * @param dataPointFactory   the factory function to create the data point using the initializer
     * @param exportGetter       the function to get the numerical value from the data point for export
     * @param <I>                the type of the initializer used to create the data point
     * @param <D>                the type of the data point
     * @return the builder
     */
    @NonNull
    static <I, D> Builder<I, D> builder(
            @NonNull String name,
            @NonNull I defaultInitializer,
            @NonNull Function<I, D> dataPointFactory,
            @NonNull Function<D, Number> exportGetter) {
        return builder(key(name), defaultInitializer, dataPointFactory, exportGetter);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric key.
     * The data point will be created using the given factory function without any initializer.
     *
     * @param key              the metric key
     * @param dataPointFactory the factory function to create the data point
     * @param exportGetter     the function to get the numerical value from the data point for export
     * @param <D>              the type of the data point
     * @return the builder
     */
    @NonNull
    static <D> Builder<Object, D> builder(
            @NonNull MetricKey<GaugeAdapter<Object, D>> key,
            @NonNull Supplier<D> dataPointFactory,
            @NonNull Function<D, Number> exportGetter) {
        return new Builder<>(key, NO_DEFAULT_INITIALIZER, init -> dataPointFactory.get(), exportGetter);
    }

    /**
     * Create a builder for a {@link GaugeAdapter} with the given metric name.
     * The data point will be created using the given factory function without any initializer.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name             the metric name
     * @param dataPointFactory the factory function to create the data point
     * @param exportGetter     the function to get the numerical value from the data point for export
     * @param <D>              the type of the data point
     * @return the builder
     */
    static <D> Builder<Object, D> builder(
            @NonNull String name, @NonNull Supplier<D> dataPointFactory, @NonNull Function<D, Number> exportGetter) {
        return builder(key(name), dataPointFactory, exportGetter);
    }

    /**
     * Builder for {@link GaugeAdapter}.
     */
    final class Builder<I, D> extends StatefulMetric.Builder<I, D, Builder<I, D>, GaugeAdapter<I, D>> {

        private final Function<D, Number> exportGetter;
        private Consumer<D> reset;

        /**
         * Create a builder for a {@link GaugeAdapter} with the given metric key.
         *
         * @param key                the metric key
         * @param defaultInitializer the default initializer used to create the data point
         * @param dataPointFactory   the factory function to create the data point using the initializer
         * @param exportGetter       the function to get the numerical value from the data point for export
         */
        private Builder(
                @NonNull MetricKey<GaugeAdapter<I, D>> key,
                @NonNull I defaultInitializer,
                @NonNull Function<I, D> dataPointFactory,
                @NonNull Function<D, Number> exportGetter) {
            super(MetricType.GAUGE, key, defaultInitializer, dataPointFactory);
            this.exportGetter = Objects.requireNonNull(exportGetter, "Export getter must not be null");
        }

        /**
         * Get the function to get the numerical value from the data point for export.
         *
         * @return the export getter function
         */
        @NonNull
        public Function<D, Number> getExportGetter() {
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
        public Builder<I, D> withReset(@NonNull Consumer<D> reset) {
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
        protected GaugeAdapter<I, D> buildMetric() {
            return new DefaultGaugeAdapter<>(this);
        }

        /**
         * @return this builder
         */
        @NonNull
        @Override
        protected Builder<I, D> self() {
            return this;
        }
    }
}
