// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@code FunctionGauge} maintains a single value.
 * <p>
 * Unlike the other gauges, the value of a {@code FunctionGauge} is not explicitly set. Instead,
 * a {@link java.util.function.Supplier} has to be specified, which reads the current value of this gauge.
 * <p>
 * Only the current value is stored, no history or distribution is kept.
 *
 * @param <T> the type of the contained value
 */
public interface FunctionGauge<T> extends Metric {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default MetricType getMetricType() {
        return MetricType.GAUGE;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default EnumSet<ValueType> getValueTypes() {
        return EnumSet.of(VALUE);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default T get(@NonNull final ValueType valueType) {
        Objects.requireNonNull(valueType, "valueType must not be null");
        if (valueType == VALUE) {
            return get();
        }
        throw new IllegalArgumentException("Unsupported ValueType: " + valueType);
    }

    /**
     * Get the current value
     *
     * @return the current value
     */
    T get();

    /**
     * Configuration of a {@link FunctionGauge}
     *
     * @param <T> the type of the value that will be contained in the {@code FunctionGauge}
     */
    final class Config<T> extends PlatformMetricConfig<FunctionGauge<T>, Config<T>> {

        private final Class<T> type;
        private final Supplier<T> supplier;

        /**
         * Constructor of {@code FunctionGauge.Config}
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @param type
         * 		the type of the values this {@code FunctionGauge} returns
         * @param supplier
         * 		the {@code Supplier} of the value of this {@code Gauge}
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final Class<T> type,
                @NonNull final Supplier<T> supplier) {
            super(category, name, "%s");
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.supplier = Objects.requireNonNull(supplier, "supplier must not be null");
        }

        /**
         * Constructor of {@code FunctionGauge.Config}
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @param description metric description
         * @param unit the unit for metric
         * @param format the format for metric
         * @param type
         * 		the type of the values this {@code FunctionGauge} returns
         * @param supplier the format for metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        private Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final String description,
                @NonNull final String unit,
                @NonNull final String format,
                @NonNull final Class<T> type,
                @NonNull final Supplier<T> supplier) {
            super(category, name, description, unit, format);
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.supplier = Objects.requireNonNull(supplier, "supplier must not be null");
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public FunctionGauge.Config<T> withDescription(@NonNull final String description) {
            return new FunctionGauge.Config<>(
                    getCategory(), getName(), description, getUnit(), getFormat(), getType(), getSupplier());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public FunctionGauge.Config<T> withUnit(@NonNull final String unit) {
            return new FunctionGauge.Config<>(
                    getCategory(), getName(), getDescription(), unit, getFormat(), getType(), getSupplier());
        }

        /**
         * Sets the {@link Metric#getFormat() Metric.format} in fluent style.
         *
         * @param format
         * 		the format-string
         * @return a new configuration-object with updated {@code format}
         * @throws IllegalArgumentException
         * 		if {@code format} is {@code null} or consists only of whitespaces
         */
        @NonNull
        public FunctionGauge.Config<T> withFormat(@NonNull final String format) {
            return new FunctionGauge.Config<>(
                    getCategory(), getName(), getDescription(), getUnit(), format, getType(), getSupplier());
        }

        /**
         * Getter of the type of the returned values
         *
         * @return the type of the returned values
         */
        @NonNull
        public Class<T> getType() {
            return type;
        }

        /**
         * Getter of the {@code supplier}
         *
         * @return the {@code supplier}
         */
        @NonNull
        public Supplier<T> getSupplier() {
            return supplier;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @SuppressWarnings("unchecked")
        @Override
        public Class<FunctionGauge<T>> getResultClass() {
            return (Class<FunctionGauge<T>>) (Class<?>) FunctionGauge.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public FunctionGauge<T> create(@NonNull final PlatformMetricsFactory factory) {
            return factory.createFunctionGauge(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .appendSuper(super.toString())
                    .append("type", type.getName())
                    .toString();
        }
    }
}
