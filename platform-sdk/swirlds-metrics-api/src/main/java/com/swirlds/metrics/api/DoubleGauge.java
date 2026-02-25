// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Objects;

/**
 * A {@code DoubleGauge} stores a single {@code double} value.
 * <p>
 * Only the current value is stored, no history or distribution is kept. Special values ({@link Double#NaN},
 * {@link Double#POSITIVE_INFINITY}, {@link Double#NEGATIVE_INFINITY}) are supported.
 */
public interface DoubleGauge extends Metric {

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
        return DataType.FLOAT;
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
    @Override
    @NonNull
    default Double get(@NonNull final ValueType valueType) {
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
    double get();

    /**
     * Set the current value
     * <p>
     * {@link Double#NaN}, {@link Double#POSITIVE_INFINITY}, {@link Double#NEGATIVE_INFINITY} are supported.
     *
     * @param newValue the new value
     */
    void set(final double newValue);

    /**
     * Modify the current value by adding passed parameter. Supports negative values which will result in subtraction.
     *
     * @param change
     * 		value change
     */
    void add(final double change);

    /**
     * Configuration of a {@link DoubleGauge}
     */
    final class Config extends MetricConfig<DoubleGauge, Config> {

        private final double initialValue;

        /**
         * Constructor of {@code DoubleGauge.Config}
         * <p>
         * The {@link #getInitialValue() initialValue} is by default set to {@code 0.0}
         * <p>
         * The initial value is set to {@code 0.0}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name     a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name, FloatFormats.FORMAT_11_3);
            this.initialValue = 0.0;
        }

        /**
         * Constructor of {@code DoubleGauge.Config}
         *
         *
         * @param category     the kind of metric (metrics are grouped or filtered by this)
         * @param name         a short name for the metric
         * @param description  metric description
         * @param unit         metric unit
         * @param format       format for metric
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
                final double initialValue) {

            super(category, name, description, unit, format);
            this.initialValue = initialValue;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public DoubleGauge.Config withDescription(@NonNull final String description) {
            return new DoubleGauge.Config(
                    getCategory(), getName(), description, getUnit(), getFormat(), getInitialValue());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public DoubleGauge.Config withUnit(@NonNull final String unit) {
            return new DoubleGauge.Config(
                    getCategory(), getName(), getDescription(), unit, getFormat(), getInitialValue());
        }

        /**
         * Sets the {@link Metric#getFormat() Metric.format} in fluent style.
         *
         * @param format the format-string
         * @return a new configuration-object with updated {@code format}
         * @throws NullPointerException     if {@code format} is {@code null}
         * @throws IllegalArgumentException if {@code format} consists only of whitespaces
         */
        @NonNull
        public DoubleGauge.Config withFormat(@NonNull final String format) {
            return new DoubleGauge.Config(
                    getCategory(), getName(), getDescription(), getUnit(), format, getInitialValue());
        }

        /**
         * Getter of the {@code initialValue}
         *
         * @return the {@code initialValue}
         */
        public double getInitialValue() {
            return initialValue;
        }

        /**
         * Fluent-style setter of the initial value.
         *
         * @param initialValue the initial value
         * @return a new configuration-object with updated {@code initialValue}
         */
        @NonNull
        public DoubleGauge.Config withInitialValue(final double initialValue) {
            return new DoubleGauge.Config(
                    getCategory(), getName(), getDescription(), getUnit(), getFormat(), initialValue);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<DoubleGauge> getResultClass() {
            return DoubleGauge.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public DoubleGauge create(final MetricsFactory factory) {
            return factory.createDoubleGauge(this);
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
