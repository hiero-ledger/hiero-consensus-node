// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.core.ToLongOrDoubleFunction;
import org.hiero.metrics.api.datapoint.GaugeDataPoint;
import org.hiero.metrics.internal.GenericDoubleGaugeImpl;
import org.hiero.metrics.internal.GenericLongGaugeImpl;
import org.hiero.metrics.internal.datapoint.AtomicReferenceGaugeDataPoint;

/**
 * A stateful metric of type {@link MetricType#GAUGE} that holds {@link GaugeDataPoint} per label set. <br>
 * Data points simply hold last observed custom value (convertable to numerical value on export).
 *
 * @param <T> the type of value used to observe/update the gauge and convert to {@code double} or {@code long} for export
 */
public interface GenericGauge<T> extends StatefulMetric<Supplier<T>, GaugeDataPoint<T>> {

    /**
     * Create a metric key for a {@link GenericGauge} with the given name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @param <T>  the type of value used to observe/update the gauge
     * @return the metric key
     */
    @NonNull
    static <T> MetricKey<GenericGauge<T>> key(@NonNull String name) {
        return MetricKey.of(name, GenericGauge.class);
    }

    /**
     * Create a builder for a {@link GenericGauge} with the given metric key and value converter.
     *
     * @param key            the metric key
     * @param valueConverter the function to convert the value to {@code double} or {@code long} for export
     * @param <T>            the type of value used to observe/update the gauge
     * @return the builder
     */
    @NonNull
    static <T> Builder<T> builder(
            @NonNull MetricKey<GenericGauge<T>> key, @NonNull ToLongOrDoubleFunction<T> valueConverter) {
        return new Builder<>(key, valueConverter);
    }

    /**
     * Create a builder for a {@link GenericGauge} with the given name and {@code double} value converter. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name           the name of the metric
     * @param valueConverter the function to convert the value to {@code double} or {@code long}  for export
     * @param <T>            the type of value used to observe/update the gauge
     * @return the builder
     */
    @NonNull
    static <T> Builder<T> builder(@NonNull String name, @NonNull ToLongOrDoubleFunction<T> valueConverter) {
        return builder(key(name), valueConverter);
    }

    /**
     * Create a function to convert {@link Duration} to {@code long} using the specified {@link ChronoUnit}.
     *
     * @param unit the chrono unit to convert the duration to {@code long} for export
     * @return the function
     */
    static ToLongOrDoubleFunction<Duration> durationToLongFunction(@NonNull ChronoUnit unit) {
        Objects.requireNonNull(unit, "unit cannot be null");
        return new ToLongOrDoubleFunction<>((ToDoubleFunction<Duration>) duration -> duration.get(unit));
    }

    /**
     * A builder for a {@link GenericGauge} using {@link GaugeDataPoint} per label set.
     * <p>
     * Default initial value is {@code null}.
     *
     * @param <T> the type of value used to observe/update the gauge
     */
    final class Builder<T> extends StatefulMetric.Builder<Supplier<T>, GaugeDataPoint<T>, Builder<T>, GenericGauge<T>> {

        private final ToLongOrDoubleFunction<T> valueConverter;

        /**
         * Create a builder for a {@link GenericGauge} with the given metric key and {@code double} value converter.
         *
         * @param key            the metric key
         * @param valueConverter the function to convert the value to double for export
         */
        private Builder(@NonNull MetricKey<GenericGauge<T>> key, @NonNull ToLongOrDoubleFunction<T> valueConverter) {
            super(MetricType.GAUGE, key, () -> null, AtomicReferenceGaugeDataPoint::new);
            this.valueConverter = Objects.requireNonNull(valueConverter, "valueConverter cannot be null");
        }

        /**
         * Get the function holder to convert the value to {@code double} or {@code long} for export.
         *
         * @return the value converter function
         */
        @NonNull
        public ToLongOrDoubleFunction<T> getValueConverter() {
            return valueConverter;
        }

        /**
         * Set the initial value for the gauge.
         *
         * @param initValue the initial value
         * @return this builder
         */
        @NonNull
        public Builder<T> withInitValue(@Nullable T initValue) {
            return withDefaultInitializer(() -> initValue);
        }

        /**
         * Build the {@link GenericGauge} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public GenericGauge<T> buildMetric() {
            if (valueConverter.isToDoubleFunction()) {
                return new GenericLongGaugeImpl<>(this);
            } else {
                return new GenericDoubleGaugeImpl<>(this);
            }
        }

        /**
         * @return this builder
         */
        @NonNull
        @Override
        protected Builder<T> self() {
            return this;
        }
    }
}
