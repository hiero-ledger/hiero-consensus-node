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
import org.hiero.metrics.api.datapoint.GaugeDataPoint;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.api.utils.Unit;
import org.hiero.metrics.internal.DefaultGenericGauge;
import org.hiero.metrics.internal.datapoint.AtomicReferenceGaugeDataPoint;

/**
 * A stateful metric of type {@link MetricType#GAUGE} that holds {@link GaugeDataPoint} per label set. <br>
 * Data points simply hold last observed custom value (convertable to numerical value on export).
 *
 * @param <T> the type of value used to observe/update the gauge and convert to double for export
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
     * @param valueConverter the function to convert the value to double for export
     * @param <T>            the type of value used to observe/update the gauge
     * @return the builder
     */
    @NonNull
    static <T> Builder<T> builder(
            @NonNull MetricKey<GenericGauge<T>> key, @NonNull ToDoubleFunction<T> valueConverter) {
        return new Builder<>(key, valueConverter);
    }

    /**
     * Create a builder for a {@link GenericGauge} with the given name and value converter. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name           the name of the metric
     * @param valueConverter the function to convert the value to double for export
     * @param <T>            the type of value used to observe/update the gauge
     * @return the builder
     */
    @NonNull
    static <T> Builder<T> builder(@NonNull String name, @NonNull ToDoubleFunction<T> valueConverter) {
        return builder(key(name), valueConverter);
    }

    /**
     * Create a builder for a {@link GenericGauge} with the given metric key for {@link Duration} values.
     * The duration will be converted to double using the specified {@link ChronoUnit}.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name  the metric name
     * @param unit the chrono unit to convert the duration to double for export
     * @return the builder
     */
    @NonNull
    static Builder<Duration> durationBuilder(@NonNull String name, @NonNull ChronoUnit unit) {
        Objects.requireNonNull(unit, "unit cannot be null");
        final MetricKey<GenericGauge<Duration>> key = key(name);
        return new Builder<>(key, duration -> duration == null ? StatUtils.ZERO : duration.get(unit))
                .withUnit(Unit.getUnit(unit));
    }

    /**
     * Create a builder for a {@link GenericGauge} with the given metric key for {@link Enum} values.
     * The enum will be converted to double using its ordinal value. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @param <E>  the type of the enum
     * @return the builder
     */
    @NonNull
    static <E extends Enum<E>> Builder<E> enumGauge(@NonNull String name) {
        return new Builder<>(key(name), Enum::ordinal);
    }

    /**
     * A builder for a {@link GenericGauge} using {@link GaugeDataPoint} per label set.
     * <p>
     * Default initial value is {@code null}.
     *
     * @param <T> the type of value used to observe/update the gauge
     */
    final class Builder<T> extends StatefulMetric.Builder<Supplier<T>, GaugeDataPoint<T>, Builder<T>, GenericGauge<T>> {

        /**
         * Create a builder for a {@link GenericGauge} with the given metric key and value converter.
         *
         * @param key            the metric key
         * @param valueConverter the function to convert the value to double for export
         */
        private Builder(@NonNull MetricKey<GenericGauge<T>> key, @NonNull ToDoubleFunction<T> valueConverter) {
            super(MetricType.GAUGE, key, () -> null, init -> new AtomicReferenceGaugeDataPoint<>(init, valueConverter));
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
            return new DefaultGenericGauge<>(this);
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
