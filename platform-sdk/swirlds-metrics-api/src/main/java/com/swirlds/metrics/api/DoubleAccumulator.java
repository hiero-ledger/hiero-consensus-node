// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_11_3;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
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
    default Set<ValueType> getValueTypes() {
        return SINGLE_VALUE_TYPE_SET;
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

        private @NonNull DoubleBinaryOperator accumulator = Double::max;
        private @NonNull DoubleSupplier initializer = DOUBLE_DEFAULT_INITIALIZER;

        /**
         * Constructor of {@code DoubleAccumulator.Config}
         * <p>
         * By default, the {@link #getAccumulator() accumulator} is set to {@code Double::max}, the
         * {@link #getInitialValue() initialValue} is set to {@code 0.0}, and {@link #getFormat() format} is set to
         * {@value FloatFormats#FORMAT_11_3}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name     a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name);
            withFormat(FORMAT_11_3);
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
        @NonNull
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
         * @return self-reference
         */
        @NonNull
        public DoubleAccumulator.Config withAccumulator(@NonNull final DoubleBinaryOperator accumulator) {
            this.accumulator = Objects.requireNonNull(accumulator, "accumulator must not be null");
            return this;
        }

        /**
         * Fluent-style setter of the initial value.
         *
         * @param initialValue the initial value
         * @return self-reference
         */
        @NonNull
        public DoubleAccumulator.Config withInitialValue(final double initialValue) {
            if (initialValue == 0.0) {
                return withInitializer(DOUBLE_DEFAULT_INITIALIZER);
            }
            return withInitializer(() -> initialValue);
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
        public DoubleAccumulator.Config withInitializer(@NonNull final DoubleSupplier initializer) {
            this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
            return this;
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
        protected Config self() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected ToStringBuilder selfToString() {
            return super.selfToString().append("initialValue", initializer.getAsDouble());
        }
    }
}
