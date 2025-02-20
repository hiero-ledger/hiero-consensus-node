// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_11_3;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleSupplier;

/**
 * A {@code DoubleAccumulator} accumulates a {@code double}-value.
 * <p>
 * It is reset in regular intervals. The exact timing depends on the implementation.
 * <p>
 * A {@code DoubleAccumulator} is reset to the {@link #getInitialValue() initialValue}. If no {@code initialValue} was
 * specified, the {@code DoubleAccumulator} is reset to {@code 0.0}.
 */
public interface DoubleAccumulator extends Metric {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default MetricType getMetricType() {
        return MetricType.ACCUMULATOR;
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
    @NonNull
    @Override
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
     * Returns the {@code initialValue} of the {@code DoubleAccumulator}
     *
     * @return the initial value
     */
    double getInitialValue();

    /**
     * Atomically updates the current value with the results of applying the {@code accumulator}-function of this
     * {@code DoubleAccumulator} to the current and given value.
     * <p>
     * The function is applied with the current value as its first argument, and the provided {@code other} as the
     * second argument.
     *
     * @param other the second parameter
     */
    void update(final double other);

    /**
     * Configuration of a {@link DoubleAccumulator}
     */
    final class Config extends MetricConfig<DoubleAccumulator, DoubleAccumulator.Config> {

        private final @NonNull DoubleBinaryOperator accumulator;
        private final @Nullable DoubleSupplier initializer;
        private final double initialValue;

        /**
         * Constructor of {@code DoubleAccumulator.Config}
         * <p>
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Double::max}, the
         * {@link #getInitialValue() initialValue} is set to {@code 0.0}, and {@link #getFormat() format} is set to
         * {@link FloatFormats#FORMAT_11_3}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name     a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name, FORMAT_11_3);
            this.accumulator = Double::max;
            this.initializer = null;
            this.initialValue = 0.0;
        }

        /**
         * Constructor of {@code DoubleAccumulator.Config}
         * <p>
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Double::max}, the
         * {@link #getInitialValue() initialValue} is set to {@code 0.0}, and {@link #getFormat() format} is set to
         * {@link FloatFormats#FORMAT_11_3}.
         *
         * @param category    the kind of metric (metrics are grouped or filtered by this)
         * @param name        a short name for the metric
         * @param description metric description
         * @param unit        metric unit
         * @param accumulator accumulator for metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        private Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final String description,
                @NonNull final String unit,
                @NonNull final String format,
                @NonNull final DoubleBinaryOperator accumulator,
                @Nullable final DoubleSupplier initializer,
                final double initialValue) {

            super(category, name, description, unit, format);
            this.accumulator = Objects.requireNonNull(accumulator, "accumulator must not be null");
            this.initializer = initializer;
            this.initialValue = initialValue;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public DoubleAccumulator.Config withDescription(@NonNull final String description) {
            return new DoubleAccumulator.Config(
                    getCategory(),
                    getName(),
                    description,
                    getUnit(),
                    getFormat(),
                    getAccumulator(),
                    getInitializer(),
                    getInitialValue());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public DoubleAccumulator.Config withUnit(@NonNull final String unit) {
            return new DoubleAccumulator.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    unit,
                    getFormat(),
                    getAccumulator(),
                    getInitializer(),
                    getInitialValue());
        }

        /**
         * Sets the {@link Metric#getFormat() Metric.format} in fluent style.
         *
         * @param format the format-string
         * @return a new configuration-object with updated {@code format}
         * @throws NullPointerException     if {@code format} is {@code null}
         * @throws IllegalArgumentException if {@code format} consists only of whitespaces
         */
        public DoubleAccumulator.Config withFormat(@NonNull final String format) {
            return new DoubleAccumulator.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    getUnit(),
                    format,
                    getAccumulator(),
                    getInitializer(),
                    getInitialValue());
        }

        /**
         * Getter of the {@code accumulator}
         *
         * @return the accumulator
         */
        @NonNull
        public DoubleBinaryOperator getAccumulator() {
            return accumulator;
        }

        /**
         * Getter of the {@code initializer}
         *
         * @return the initializer
         */
        @Nullable
        public DoubleSupplier getInitializer() {
            return initializer;
        }

        /**
         * Fluent-style setter of the accumulator.
         * <p>
         * The accumulator should be side-effect-free, since it may be re-applied when attempted updates fail due to
         * contention among threads.
         *
         * @param accumulator The {@link DoubleBinaryOperator} that is used to accumulate the value.
         * @return a new configuration-object with updated {@code initialValue}
         */
        @NonNull
        public DoubleAccumulator.Config withAccumulator(@NonNull final DoubleBinaryOperator accumulator) {
            return new DoubleAccumulator.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    getUnit(),
                    getFormat(),
                    accumulator,
                    getInitializer(),
                    getInitialValue());
        }

        /**
         * Getter of the initial value
         *
         * @return the initial value
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
        public DoubleAccumulator.Config withInitialValue(final double initialValue) {
            return new DoubleAccumulator.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    getUnit(),
                    getFormat(),
                    getAccumulator(),
                    getInitializer(),
                    initialValue);
        }

        /**
         * Fluent-style setter of the initial value.
         * <p>
         * If both {@code initializer} and {@code initialValue} are set, the {@code initialValue} is ignored
         *
         * @param initializer the initializer
         * @return a new configuration-object with updated {@code initializer}
         */
        @NonNull
        public DoubleAccumulator.Config withInitializer(@NonNull final DoubleSupplier initializer) {
            return new DoubleAccumulator.Config(
                    getCategory(),
                    getName(),
                    getDescription(),
                    getUnit(),
                    getFormat(),
                    getAccumulator(),
                    Objects.requireNonNull(initializer, "initializer"),
                    getInitialValue());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<DoubleAccumulator> getResultClass() {
            return DoubleAccumulator.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public DoubleAccumulator create(@NonNull final MetricsFactory factory) {
            return factory.createDoubleAccumulator(this);
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
