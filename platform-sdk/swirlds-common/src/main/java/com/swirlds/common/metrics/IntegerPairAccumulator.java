// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;

/**
 * An {@code IntegerPairAccumulator} accumulates two {@code int}-values, which can be updated atomically.
 * <p>
 * When reading the value of an {@code IntegerPairAccumulator}, both {@code ints} are combined.
 * <p>
 * An {@code IntegerAccumulator} is reset in regular intervals. The exact timing depends on the implementation.
 *
 * @param <T> The type of the combined value
 */
public interface IntegerPairAccumulator<T> extends Metric {

    BiFunction<Integer, Integer, Double> AVERAGE = (sum, count) -> {
        if (count == 0) {
            // avoid division by 0
            return 0.0;
        }
        return ((double) sum) / count;
    };

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
    default Set<ValueType> getValueTypes() {
        return SINGLE_VALUE_TYPE_SET;
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
    @NonNull
    T get();

    /**
     * Returns the left {@code int}-value
     *
     * @return the left value
     */
    int getLeft();

    /**
     * Returns the right {@code int}-value
     *
     * @return the right value
     */
    int getRight();

    /**
     * Atomically updates the current values with the results of applying the {@code accumulators} of this
     * {@code IntegerPairAccumulator} to the current and given values.
     * <p>
     * The function is applied with the current values as their first argument, and the provided {@code leftValue} and
     * {@code rightValue }as the second arguments.
     *
     * @param leftValue  the value combined with the left {@code int}
     * @param rightValue the value combined with the right {@code int}
     */
    void update(final int leftValue, final int rightValue);

    /**
     * Configuration of a {@link IntegerPairAccumulator}
     */
    final class Config<T> extends PlatformMetricConfig<IntegerPairAccumulator<T>, Config<T>> {

        private final @NonNull Class<T> type;
        private final @NonNull BiFunction<Integer, Integer, T> resultFunction;

        private @NonNull IntBinaryOperator leftAccumulator;
        private @NonNull IntBinaryOperator rightAccumulator;

        private @NonNull IntSupplier leftInitializer;
        private @NonNull IntSupplier rightInitializer;

        /**
         * Constructor of {@code IntegerPairAccumulator.Config}
         * <p>
         * The accumulators are by default set to {@code Integer::sum}.
         *
         * @param category       the kind of metric (metrics are grouped or filtered by this)
         * @param name           a short name for the metric
         * @param type           the type of the values this {@code IntegerPairAccumulator} returns
         * @param resultFunction the function that is used to calculate the {@code IntegerAccumulator}'s value.
         * @throws IllegalArgumentException if one of the parameters is {@code null} or consists only of whitespaces
         */
        public Config(
                @NonNull final String category,
                @NonNull final String name,
                @NonNull final Class<T> type,
                @NonNull final BiFunction<Integer, Integer, T> resultFunction) {

            super(category, name);
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.resultFunction = Objects.requireNonNull(resultFunction, "resultFunction must not be null");
            this.leftAccumulator = Integer::sum;
            this.rightAccumulator = Integer::sum;
            this.leftInitializer = INT_DEFAULT_INITIALIZER;
            this.rightInitializer = INT_DEFAULT_INITIALIZER;
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
         * Getter of the {@code resultFunction}
         *
         * @return the {@code resultFunction}
         */
        @NonNull
        public BiFunction<Integer, Integer, T> getResultFunction() {
            return resultFunction;
        }

        /**
         * Getter of the {@code leftAccumulator}
         *
         * @return the {@code leftAccumulator}
         */
        @NonNull
        public IntBinaryOperator getLeftAccumulator() {
            return leftAccumulator;
        }

        /**
         * Fluent-style setter of the left accumulator.
         *
         * @param leftAccumulator the left accumulator
         * @return a new configuration-object with updated {@code leftAccumulator}
         */
        @NonNull
        public IntegerPairAccumulator.Config<T> withLeftAccumulator(@NonNull final IntBinaryOperator leftAccumulator) {
            this.leftAccumulator = Objects.requireNonNull(leftAccumulator, "leftAccumulator must not be null");
            return this;
        }

        /**
         * Getter of the {@code rightAccumulator}
         *
         * @return the {@code rightAccumulator}
         */
        @NonNull
        public IntBinaryOperator getRightAccumulator() {
            return rightAccumulator;
        }

        /**
         * Fluent-style setter of the right accumulator.
         *
         * @param rightAccumulator the right accumulator value
         * @return a new configuration-object with updated {@code rightAccumulator}
         */
        @NonNull
        public IntegerPairAccumulator.Config<T> withRightAccumulator(
                @NonNull final IntBinaryOperator rightAccumulator) {
            this.rightAccumulator = Objects.requireNonNull(rightAccumulator, "rightAccumulator must not be null");
            return this;
        }

        /**
         * Getter of the {@code leftInitializer}
         *
         * @return the {@code leftInitializer}
         */
        @NonNull
        public IntSupplier getLeftInitializer() {
            return leftInitializer;
        }

        /**
         * Fluent-style setter of the left initializer.
         *
         * @param leftInitializer the left initializer
         * @return a new configuration-object with updated {@code leftInitializer}
         */
        @NonNull
        public IntegerPairAccumulator.Config<T> withLeftInitializer(@NonNull final IntSupplier leftInitializer) {
            this.leftInitializer = Objects.requireNonNull(leftInitializer, "leftInitializer must not be null");
            return this;
        }

        /**
         * Fluent-style setter for a constant left initial value
         *
         * @param leftInitialValue the left initial value
         * @return a new configuration-object with updated {@code leftInitializer}
         */
        @NonNull
        public IntegerPairAccumulator.Config<T> withLeftInitialValue(final int leftInitialValue) {
            if (leftInitialValue == 0) {
                return withLeftInitializer(INT_DEFAULT_INITIALIZER);
            }
            return withLeftInitializer(() -> leftInitialValue);
        }

        /**
         * Getter of the {@code rightInitializer}
         *
         * @return the {@code rightInitializer}
         */
        @NonNull
        public IntSupplier getRightInitializer() {
            return rightInitializer;
        }

        /**
         * Fluent-style setter of the right initializer.
         *
         * @param rightInitializer the right initializer value
         * @return a new configuration-object with updated {@code rightInitializer}
         */
        @NonNull
        public IntegerPairAccumulator.Config<T> withRightInitializer(@NonNull final IntSupplier rightInitializer) {
            this.rightInitializer = Objects.requireNonNull(rightInitializer, "rightInitializer must not be null");
            return this;
        }

        /**
         * Fluent-style setter for a constant right initial value
         *
         * @param rightInitialValue the right initial value
         * @return a new configuration-object with updated {@code rightInitializer}
         */
        @NonNull
        public IntegerPairAccumulator.Config<T> withRightInitialValue(final int rightInitialValue) {
            if (rightInitialValue == 0) {
                return withRightInitializer(INT_DEFAULT_INITIALIZER);
            }
            return withRightInitializer(() -> rightInitialValue);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @SuppressWarnings("unchecked")
        @Override
        public Class<IntegerPairAccumulator<T>> getResultClass() {
            return (Class<IntegerPairAccumulator<T>>) (Class<?>) IntegerPairAccumulator.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public IntegerPairAccumulator<T> create(@NonNull final PlatformMetricsFactory factory) {
            return factory.createIntegerPairAccumulator(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Config<T> self() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected ToStringBuilder selfToString() {
            return super.selfToString().append("type", type.getName());
        }
    }
}
