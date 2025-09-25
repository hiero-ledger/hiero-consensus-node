// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;

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
    default Set<ValueType> getValueTypes() {
        return SINGLE_VALUE_TYPE_SET;
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

        private double initialValue;

        /**
         * Constructor of {@code DoubleGauge.Config}
         * <p>
         * The initial value is set to {@code 0.0} and format is set to {@value FloatFormats#FORMAT_11_3}.
         *
         * @param category the kind of metric (metrics are grouped or filtered by this)
         * @param name     a short name for the metric
         * @throws NullPointerException     if one of the parameters is {@code null}
         * @throws IllegalArgumentException if one of the parameters consists only of whitespaces
         */
        public Config(@NonNull final String category, @NonNull final String name) {
            super(category, name);
            withFormat(FloatFormats.FORMAT_11_3);
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
         * @return self-reference
         */
        @NonNull
        public DoubleGauge.Config withInitialValue(final double initialValue) {
            this.initialValue = initialValue;
            return this;
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
