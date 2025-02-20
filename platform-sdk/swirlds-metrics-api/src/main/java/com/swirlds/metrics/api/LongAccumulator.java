// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import java.util.function.LongSupplier;

/**
 * An {@code LongAccumulator} accumulates a {@code long}-value.
 * <p>
 * It is reset in regular intervals. The exact timing depends on the implementation.
 * <p>
 * A {@code LongAccumulator} is reset to the {@link #getInitialValue() initialValue}.
 * If no {@code initialValue} was specified, the {@code LongAccumulator} is reset to {@code 0L}.
 */
public interface LongAccumulator extends Metric {

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
     * Returns the {@code initialValue} of the {@code LongAccumulator}
     *
     * @return the initial value
     */
    long getInitialValue();

    /**
     * Atomically updates the current value with the results of applying the {@code operator} of this
     * {@code LongAccumulator} to the current and given value.
     * <p>
     * The function is applied with the current value as its first argument, and the provided {@code other} as the
     * second argument.
     *
     * @param other
     * 		the update value
     */
    void update(final long other);

    /**
     * Configuration of a {@link LongAccumulator}
     */
    final class Config extends MetricConfig<LongAccumulator, LongAccumulator.Config> {

        private final @NonNull LongBinaryOperator accumulator;
        private final @Nullable LongSupplier initializer;

        private final long initialValue;

        /**
         * Constructor of {@code LongAccumulator.Config}
         *
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Long::max},
         * the {@link #getInitialValue() initialValue} is set to {@code 0L},
         * and {@link #getFormat() format} is set to {@code "%d"}.
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @throws IllegalArgumentException
         * 		if one of the parameters is {@code null} or consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name, "%d");
            this.accumulator = Long::max;
            this.initializer = null;
            this.initialValue = 0L;
        }

        /**
         * Constructor of {@code LongAccumulator.Config}
         *
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Long::max},
         * the {@link #getInitialValue() initialValue} is set to {@code 0L},
         * and {@link #getFormat() format} is set to {@code "%d"}.
         *
         * @param category
         * 		the kind of metric (metrics are grouped or filtered by this)
         * @param name
         * 		a short name for the metric
         * @param description metric description
         * @param unit        metric unit
         * @param accumulator accumulator for metric
         * @param initializer initializer for metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        private Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final String description,
                @NonNull final String unit,
                @NonNull final String format,
                @NonNull final LongBinaryOperator accumulator,
                @Nullable final LongSupplier initializer,
                final long initialValue) {

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
        public LongAccumulator.Config withDescription(@NonNull final String description) {
            return new LongAccumulator.Config(
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
        public LongAccumulator.Config withUnit(@NonNull final String unit) {
            return new LongAccumulator.Config(
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
         * @param format
         * 		the format-string
         * @return a new configuration-object with updated {@code format}
         * @throws NullPointerException     if {@code format} is {@code null}
         * @throws IllegalArgumentException if {@code format} consists only of whitespaces
         */
        @NonNull
        public LongAccumulator.Config withFormat(@NonNull final String format) {
            return new LongAccumulator.Config(
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
        public LongBinaryOperator getAccumulator() {
            return accumulator;
        }

        /**
         * Getter of the {@code initializer}
         *
         * @return the initializer
         */
        @Nullable
        public LongSupplier getInitializer() {
            return initializer;
        }

        /**
         * Fluent-style setter of the accumulator.
         * <p>
         * The accumulator should be side-effect-free, since it may be re-applied when attempted updates fail
         * due to contention among threads.
         *
         * @param accumulator
         * 		The {@link LongBinaryOperator} that is used to accumulate the value.
         * @return a new configuration-object with updated {@code initialValue}
         */
        @NonNull
        public LongAccumulator.Config withAccumulator(@NonNull final LongBinaryOperator accumulator) {
            return new LongAccumulator.Config(
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
         * Fluent-style setter of the initial value.
         * <p>
         * If both {@code initializer} and {@code initialValue} are set, the {@code initialValue} is ignored
         *
         * @param initializer
         * 		the initializer
         * @return a new configuration-object with updated {@code initializer}
         */
        @NonNull
        public LongAccumulator.Config withInitializer(@NonNull final LongSupplier initializer) {
            return new LongAccumulator.Config(
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
         * Getter of the {@link LongAccumulator#getInitialValue() initialValue}
         *
         * @return the initial value
         */
        public long getInitialValue() {
            return initialValue;
        }

        /**
         * Fluent-style setter of the initial value.
         *
         * @param initialValue
         * 		the initial value
         * @return a reference to {@code this}
         */
        @NonNull
        public LongAccumulator.Config withInitialValue(final long initialValue) {
            return new LongAccumulator.Config(
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
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<LongAccumulator> getResultClass() {
            return LongAccumulator.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public LongAccumulator create(@NonNull final MetricsFactory factory) {
            return factory.createLongAccumulator(this);
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
