// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;

/**
 * An {@code IntegerGauge} stores a single {@code int} value.
 * <p>
 * Only the current value is stored, no history or distribution is kept.
 */
public interface IntegerGauge extends Metric {

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
    default Set<ValueType> getValueTypes() {
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
     * Set the current value
     *
     * @param newValue
     * 		the new value
     */
    void set(final int newValue);

    /**
     * Modify the current value by adding passed parameter. Supports negative values which will result in subtraction.
     *
     * @param change
     * 		value change
     */
    void add(final int change);

    /**
     * Configuration of a {@link IntegerGauge}
     */
    final class Config extends MetricConfig<IntegerGauge, IntegerGauge.Config> {

        private int initialValue;

        /**
         * Constructor of {@code IntegerGauge.Config}
         *
         * The {@link #getInitialValue() initialValue} is by default set to {@code 0},
         * the {@link #getFormat() format} is set to {@value MetricConfig#NUMBER_FORMAT}.
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
         * Getter of the {@code initialValue}
         *
         * @return the {@code initialValue}
         */
        public int getInitialValue() {
            return initialValue;
        }

        /**
         * Fluent-style setter of the initial value.
         *
         * @param initialValue the initial value
         * @return self-reference
         */
        @NonNull
        public IntegerGauge.Config withInitialValue(final int initialValue) {
            this.initialValue = initialValue;
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Class<IntegerGauge> getResultClass() {
            return IntegerGauge.class;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public IntegerGauge create(@NonNull final MetricsFactory factory) {
            return factory.createIntegerGauge(this);
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
            return super.selfToString().append("initialValue", initialValue);
        }
    }
}
