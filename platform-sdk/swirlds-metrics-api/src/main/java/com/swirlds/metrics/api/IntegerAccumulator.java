// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

/**
 * An {@code IntegerAccumulator} accumulates an {@code int}-value.
 * <p>
 * It is reset in regular intervals. The exact timing depends on the implementation.
 * <p>
 * An {@code IntegerAccumulator} is reset to the {@link #getInitialValue() initialValue}.
 * If no {@code initialValue} was specified, the {@code IntegerAccumulator} is reset to {@code 0}.
 */
public interface IntegerAccumulator extends Metric {

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
        return SINGLE_VALUE_TYPE_SET;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    default Integer get(@NonNull final ValueType valueType) {
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
    int get();

    /**
     * Returns the {@code initialValue} of the {@code IntegerAccumulator}
     *
     * @return the initial value
     */
    int getInitialValue();

    /**
     * Atomically updates the current value with the results of applying the {@code accumulator}-function of this
     * {@code IntegerAccumulator} to the current and given value.
     * <p>
     * The function is applied with the current value as its first argument, and the provided {@code other} as the
     * second argument.
     *
     * @param other
     * 		the second parameter
     */
    void update(final int other);

    /**
     * Configuration of an {@link IntegerAccumulator}
     */
    final class Config extends MetricConfig<IntegerAccumulator, IntegerAccumulator.Config> {

        private @NonNull IntBinaryOperator accumulator = Integer::max;
        private @NonNull IntSupplier initializer = INT_DEFAULT_INITIALIZER;

        /**
         * Constructor of {@code IntegerGauge.Config}
         *
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Integer::max},
         * the {@link #getInitialValue() initialValue} is set to {@code 0},
         * and {@link #getFormat() format} is set to {@value NUMBER_FORMAT}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name);
            withNumberFormat();
        }

        /**
         * Getter of the {@code accumulator}
         *
         * @return the accumulator
         */
        @NonNull
        public IntBinaryOperator getAccumulator() {
            return accumulator;
        }

        /**
         * Fluent-style setter of the accumulator.
         * <p>
         * The accumulator should be side-effect-free, since it may be re-applied when attempted updates fail
         * due to contention among threads.
         *
         * @param accumulator the {@link IntBinaryOperator} that is used to accumulate the value.
         * @return self-reference
         */
        @NonNull
        public IntegerAccumulator.Config withAccumulator(@NonNull final IntBinaryOperator accumulator) {
            this.accumulator = Objects.requireNonNull(accumulator, "accumulator must not be null");
            return this;
        }

        /**
         * Getter of the {@code initializer}
         *
         * @return the initializer
         */
        @NonNull
        public IntSupplier getInitializer() {
            return initializer;
        }

        /**
         * Fluent-style setter of the initial value supplier
         *
         * @param initializer the initial value supplier
         * @return self-reference
         */
        public IntegerAccumulator.Config withInitializer(@NonNull final IntSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            return this;
        }

        /**
         * Fluent-style setter of the initial value (converts to {@link IntSupplier} returning provided value).
         *
         * @param initialValue the initial value
         * @return self-reference
         */
        @NonNull
        public IntegerAccumulator.Config withInitialValue(final int initialValue) {
            if (initialValue == 0) {
                return withInitializer(INT_DEFAULT_INITIALIZER);
            }
            return withInitializer(() -> initialValue);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<IntegerAccumulator> getResultClass() {
            return IntegerAccumulator.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public IntegerAccumulator create(@NonNull final MetricsFactory factory) {
            return factory.createIntegerAccumulator(this);
        }

        @Override
        protected Config self() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ToStringBuilder selfToString() {
            return super.selfToString().append("initialValue", initializer.getAsInt());
        }
    }
}
