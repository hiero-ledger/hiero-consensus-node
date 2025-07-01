// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;

/**
 * A single metric that is monitored here.
 */
public interface Metric {

    EnumSet<ValueType> SINGLE_VALUE_TYPE_SET = EnumSet.of(ValueType.VALUE);
    EnumSet<ValueType> ALL_VALUE_TYPES_SET = EnumSet.allOf(ValueType.class);

    /**
     * A {@code Metric} can keep track of several values, which are distinguished via the {@code MetricType}
     */
    enum ValueType {
        VALUE,
        MAX,
        MIN,
        STD_DEV
    }

    enum DataType {
        INT,
        FLOAT,
        STRING,
        BOOLEAN
    }

    /**
     * Returns the unique identifier of this {@code Metric}.
     * <p>
     * A {@code Metric} is identified by its category and name.
     *
     * @return the unique identifier
     */
    default @NonNull String getIdentifier() {
        return getCategory() + "." + getName();
    }

    /**
     * the kind of {@code Metric} (metrics are grouped or filtered by this)
     *
     * @return the category
     */
    @NonNull
    String getCategory();

    /**
     * a short name for the {@code Metric}
     *
     * @return the name
     */
    @NonNull
    String getName();

    /**
     * a one-sentence description of the {@code Metric}
     *
     * @return the description
     */
    @NonNull
    String getDescription();

    /**
     * The metric type of the {@code Metric}
     *
     * @return the metric type
     */
    @NonNull
    MetricType getMetricType();

    /**
     * The data-type of the {@code Metric}
     *
     * @return the data-type
     */
    @NonNull
    DataType getDataType();

    /**
     * The unit of the {@code Metric}
     *
     * @return the unit
     */
    @NonNull
    String getUnit();

    /**
     * a string that can be passed to String.format() to format the {@code Metric}
     *
     * @return the format
     */
    @NonNull
    String getFormat();

    /**
     * Returns a list of {@link ValueType}s that are provided by this {@code Metric}. The list of {@code ValueTypes}
     * will always be in the same order.
     * <p>
     * All values returned by this method have to be supported by an implementing class.
     *
     * @return the list of {@code ValueTypes}
     */
    @NonNull
    EnumSet<ValueType> getValueTypes();

    /**
     * Returns the current value of the given {@link ValueType}.
     * <p>
     * The option {@link ValueType#VALUE} has a special meaning in this context. It is always supported and returns the
     * "main" value of the {@code Metric}. I.e. even if you do not know the type of {@code Metric}, it is always safe to
     * call this method with the parameter {@code ValueType.VALUE}.
     *
     * @param valueType The {@code ValueType} of the requested value
     * @return the current value
     * @throws NullPointerException     if the given {@code ValueType} is {@code null}
     * @throws IllegalArgumentException if the given {@code ValueType} is not supported by this {@code Metric}
     */
    @NonNull
    Object get(@NonNull final ValueType valueType);

    /**
     * This method resets a {@code Metric}. It is for example called after startup to ensure that the startup time is
     * not taken into consideration.
     */
    void reset();

    /**
     * Overwritten {@code equals}-method. Two {@code Metric}-instances are considered equal, if they have the same
     * {@code Class}, {@link #getCategory() category}, and {@link #getName() name}.
     *
     * @param other the other {@code Object}
     * @return {@code true}, if both {@code Metric}-instances are considered equal, {@code false} otherwise
     */
    @Override
    boolean equals(final Object other);

    /**
     * Overwritten {@code hashCode}-method, that matches the overwritten {@link #equals(Object)}-method.
     *
     * @return the calculated hash-code
     */
    @Override
    int hashCode();
}
