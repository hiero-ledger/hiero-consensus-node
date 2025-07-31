// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
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
    default Set<ValueType> getValueTypes() {
        return SINGLE_VALUE_TYPE_SET;
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

        private @NonNull LongBinaryOperator accumulator = Long::max;
        private @NonNull LongSupplier initializer = LONG_DEFAULT_INITIALIZER;

        /**
         * Constructor of {@code LongAccumulator.Config}
         *
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Long::max},
         * the {@link #getInitialValue() initialValue} is set to {@code 0L},
         * and {@link #getFormat() format} is set to {@value #NUMBER_FORMAT}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name a short name for the metric
         * @throws IllegalArgumentException
         * 		if one of the parameters is {@code null} or consists only of whitespaces
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
        public LongBinaryOperator getAccumulator() {
            return accumulator;
        }

        /**
         * Getter of the {@code initializer}
         *
         * @return the initializer
         */
        @NonNull
        public LongSupplier getInitializer() {
            return initializer;
        }

        /**
         * Fluent-style setter of the accumulator.
         * <p>
         * The accumulator should be side-effect-free, since it may be re-applied when attempted updates fail
         * due to contention among threads.
         *
         * @param accumulator the {@link LongBinaryOperator} that is used to accumulate the value.
         * @return self-reference
         */
        @NonNull
        public LongAccumulator.Config withAccumulator(@NonNull final LongBinaryOperator accumulator) {
            this.accumulator = Objects.requireNonNull(accumulator, "accumulator must not be null");
            return this;
        }

        /**
         * Fluent-style setter of the initial value.
         * <p>
         * If both {@code initializer} and {@code initialValue} are set, the {@code initialValue} is ignored
         *
         * @param initializer the initializer
         * @return self-reference
         */
        @NonNull
        public LongAccumulator.Config withInitializer(@NonNull final LongSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            return this;
        }

        /**
         * Fluent-style setter of the initial value.
         *
         * @param initialValue the initial value
         * @return self-reference
         */
        @NonNull
        public LongAccumulator.Config withInitialValue(final long initialValue) {
            if (initialValue == 0L) {
                return withInitializer(LONG_DEFAULT_INITIALIZER);
            }
            return withInitializer(() -> initialValue);
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
        protected Config self() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected ToStringBuilder selfToString() {
            return super.selfToString().append("initialValue", initializer.getAsLong());
        }
    }
}
