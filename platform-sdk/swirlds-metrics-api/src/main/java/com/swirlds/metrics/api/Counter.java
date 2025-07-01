// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Objects;

/**
 * A {@code Counter} can be used to count events and similar things.
 * <p>
 * The value of a {@code Counter} is initially {@code 0} and can only be increased.
 */
public interface Counter extends Metric {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default MetricType getMetricType() {
        return MetricType.COUNTER;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default DataType getDataType() {
        return DataType.INT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default EnumSet<ValueType> getValueTypes() {
        return SINGLE_VALUE_TYPE_SET;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default Long get(@NonNull final ValueType valueType) {
        Objects.requireNonNull(valueType, "valueType must not be null");
        if (valueType == VALUE) {
            return get();
        }
        throw new IllegalArgumentException("Unsupported ValueType: " + valueType);
    }

    /**
     * Return the current value of the {@code Counter}
     *
     * @return the current value
     */
    long get();

    /**
     * Add a value to the {@code Counter}.
     * <p>
     * The value of a {@code Counter} can only increase, thus only non-negative numbers can be added.
     *
     * @param value the value that needs to be added
     * @throws IllegalArgumentException if {@code value <= 0}
     */
    void add(final long value);

    /**
     * Increase the {@code Counter} by {@code 1}.
     */
    void increment();

    /**
     * Configuration of a {@link Counter}.
     */
    final class Config extends MetricConfig<Counter, Counter.Config> {

        /**
         * Constructor of {@code Counter.Config}.
         * <p>
         * By default, the {@link Metric#getFormat() Metric.format} is set to {@value MetricConfig#NUMBER_FORMAT}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name     a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name);
            withNumberFormat();
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<Counter> getResultClass() {
            return Counter.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Counter create(@NonNull final MetricsFactory factory) {
            return factory.createCounter(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected Config self() {
            return this;
        }
    }
}
