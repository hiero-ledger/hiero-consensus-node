// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Objects;

/**
 * An {@code LongGauge} stores a single {@code long} value.
 * <p>
 * Only the current value is stored, no history or distribution is kept.
 */
public interface LongGauge extends Metric {

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
    default DataType getDataType() {
        return DataType.INT;
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
    default Long get(@NonNull final ValueType valueType) {
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
    long get();

    /**
     * Set the current value
     *
     * @param newValue
     * 		the new value
     */
    void set(final long newValue);

    /**
     * Modify the current value by adding passed parameter. Supports negative values which will result in subtraction.
     *
     * @param change
     * 		value change
     */
    void add(final long change);

    /**
     * Configuration of a {@link LongGauge}
     */
    final class Config extends MetricConfig<LongGauge, LongGauge.Config> {

        private final long initialValue;

        /**
         * Constructor of {@code LongGauge.Config}
         *
         *
         * The {@link #getInitialValue() initialValue} is by default set to {@code 0L},
         * the {@link #getFormat() format} is set to "%d".
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name, "%d");
            this.initialValue = 0L;
        }

        /**
         * Constructor of {@code LongGauge.Config}
         *
         *
         * The {@link #getInitialValue() initialValue} is by default set to {@code 0L},
         * the {@link #getFormat() format} is set to "%d".
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @param description metric description
         * @param unit        metric unit
         * @param format format for metric
         * @param initialValue initialValue for metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        private Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final String description,
                @NonNull final String unit,
                @NonNull final String format,
                final long initialValue) {

            super(category, name, description, unit, format);
            this.initialValue = initialValue;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public LongGauge.Config withDescription(@NonNull final String description) {
            return new LongGauge.Config(
                    getCategory(), getName(), description, getUnit(), getFormat(), getInitialValue());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public LongGauge.Config withUnit(@NonNull final String unit) {
            return new LongGauge.Config(
                    getCategory(), getName(), getDescription(), unit, getFormat(), getInitialValue());
        }

        /**
         * Sets the {@link Metric#getFormat() Metric.format} in fluent style.
         *
         * @param format
         * 		the format-string
         * @return a new configuration-object with updated {@code format}
         * @throws NullPointerException     if {@code format} is {@code null}
         * @throws IllegalArgumentException if {@code format} consists only of whitespaces
         */
        @NonNull
        public LongGauge.Config withFormat(@NonNull final String format) {
            return new LongGauge.Config(
                    getCategory(), getName(), getDescription(), getUnit(), format, getInitialValue());
        }

        /**
         * Getter of the {@code initialValue}
         *
         * @return the {@code initialValue}
         */
        public long getInitialValue() {
            return initialValue;
        }

        /**
         * Fluent-style setter of the initial value.
         *
         * @param initialValue
         * 		the initial value
         * @return a new configuration-object with updated {@code initialValue}
         */
        @NonNull
        public LongGauge.Config withInitialValue(final long initialValue) {
            return new LongGauge.Config(
                    getCategory(), getName(), getDescription(), getUnit(), getFormat(), initialValue);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<LongGauge> getResultClass() {
            return LongGauge.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public LongGauge create(@NonNull final MetricsFactory factory) {
            return factory.createLongGauge(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .appendSuper(super.toString())
                    .append("initialValue", initialValue)
                    .toString();
        }
    }
}
