// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import com.swirlds.base.ArgumentUtils;
import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * An instance of {@code MetricConfig} contains all configuration parameters needed to create a {@link Metric}.
 * <p>
 * This class is abstract and contains only common parameters. If you want to define the configuration for a specific
 * {@code Metric}, there are special purpose configuration objects (e.g. {@link Counter.Config}).
 * <p>
 * A {@code MetricConfig} should be used with {@link Metrics#getOrCreate(MetricConfig)} to create a new {@code Metric}
 * <p>
 * This class is designed for fluent-style setters using <i>'with'</i> prefix and is <b>mutable</b> -
 * when constructing a metric from the config, <b>avoid</b> saving and re-using the reference to {@code MetricConfig} object,
 * but use it only for the creation of the metric.
 *
 * @param <T> the {@code Class} for which the configuration is
 */
public abstract class MetricConfig<T extends Metric, C extends MetricConfig<T, C>> {

    public static final IntSupplier INT_DEFAULT_INITIALIZER = () -> 0;
    public static final LongSupplier LONG_DEFAULT_INITIALIZER = () -> 0L;
    public static final DoubleSupplier DOUBLE_DEFAULT_INITIALIZER = () -> 0.0;

    public static final String DEFAULT_FORMAT = "%s";
    public static final String NUMBER_FORMAT = "%d";

    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final @NonNull String category;
    private final @NonNull String name;

    private String description;
    private String unit;
    private String format;

    /**
     * Constructor of {@code MetricConfig}.
     * <p>
     * By default, the {@link Metric#getDescription() Metric.description} is set to the {@code name}.<br>
     * By default, the {@link Metric#getUnit() Metric.unit} is set to the empty string.<br>
     * By default, the {@link Metric#getFormat() Metric.format} is set to {@value #DEFAULT_FORMAT}.
     *
     * @param category      the kind of metric (metrics are grouped or filtered by this)
     * @param name          a short name for the metric
     * @throws NullPointerException if one of the parameters is {@code null}
     * @throws IllegalArgumentException if one of the parameters is blank
     */
    protected MetricConfig(final @NonNull String category, final @NonNull String name) {
        this.category = ArgumentUtils.throwArgBlank(category, "category");
        this.name = ArgumentUtils.throwArgBlank(name, "name");
        description = name;
        unit = "";
        format = DEFAULT_FORMAT;
    }

    /**
     * Getter of the {@link Metric#getCategory() Metric.category}
     *
     * @return the {@code category}
     */
    @NonNull
    public String getCategory() {
        return category;
    }

    /**
     * Getter of the {@link Metric#getName() Metric.name}
     *
     * @return the {@code name}
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Getter of the {@link Metric#getDescription() Metric.description}
     *
     * @return the {@code description}
     */
    @NonNull
    public String getDescription() {
        return description;
    }

    /**
     * Sets the {@link Metric#getDescription() Metric.description} in fluent style.
     *
     * @param description the description of the metric
     * @return self-reference
     * @throws NullPointerException if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is blank or too long
     */
    public @NonNull C withDescription(final String description) {
        this.description = ArgumentUtils.throwArgBlank(description, "description");
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Description has " + description.length() + " characters, must not be longer than "
                            + MAX_DESCRIPTION_LENGTH + " characters: "
                            + description);
        }
        return self();
    }

    /**
     * Getter of the {@link Metric#getUnit() Metric.unit}
     *
     * @return the {@code unit} of the metric
     */
    public @NonNull String getUnit() {
        return unit;
    }

    /**
     * Sets the {@link Metric#getUnit() Metric.unit} in fluent style.
     *
     * @param unit the unit
     * @return self-reference
     * @throws NullPointerException if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code unit} is blank
     */
    public @NonNull C withUnit(final String unit) {
        this.unit = ArgumentUtils.throwArgBlank(unit, "unit");
        return self();
    }

    /**
     * Getter of the {@link Metric#getFormat() Metric.format}
     *
     * @return the format {@code String} used to format metric values
     */
    public @NonNull String getFormat() {
        return format;
    }

    /**
     * Sets the {@link Metric#getFormat() Metric.format} in fluent style.
     *
     * @param format format string used to format metric values
     * @return self-reference
     * @throws NullPointerException if {@code format} is {@code null}
     * @throws IllegalArgumentException if {@code format} is blank
     */
    public @NonNull C withFormat(final String format) {
        this.format = ArgumentUtils.throwArgBlank(format, "format");
        return self();
    }

    /**
     * Sets the {@link Metric#getFormat() Metric.format} as defined by {@link #NUMBER_FORMAT}.
     *
     * @return self-reference
     */
    public @NonNull C withNumberFormat() {
        return withFormat(NUMBER_FORMAT);
    }

    /**
     * Class of the {@code Metric} that this configuration is meant for
     *
     * @return the {@code Class}
     */
    public abstract @NonNull Class<T> getResultClass();

    /**
     * Create a {@code Metric} using the given {@link MetricsFactory}
     * <p>
     * Implementation note: we use the double-dispatch pattern when creating a {@link Metric}. More details can be found
     * at {@link Metrics#getOrCreate(MetricConfig)}.
     *
     * @param factory the {@code MetricFactory}
     * @return the new {@code Metric}-instance
     */
    @NonNull
    public abstract T create(final MetricsFactory factory);

    /**
     * @return returns the self-reference of this configuration object.
     */
    protected abstract C self();

    /**
     * {@inheritDoc}
     */
    @Override
    public final String toString() {
        return selfToString().toString();
    }

    /**
     * @return {@link ToStringBuilder} that contains the configuration parameters of this {@code MetricConfig}
     */
    protected ToStringBuilder selfToString() {
        return new ToStringBuilder(this)
                .append("category", category)
                .append("name", name)
                .append("description", description)
                .append("unit", unit)
                .append("format", format)
                .append("resultClass", getResultClass());
    }

    public static Metric.DataType mapDataType(final Class<?> type) {
        if (Double.class.equals(type) || Float.class.equals(type)) {
            return Metric.DataType.FLOAT;
        }
        if (Number.class.isAssignableFrom(type)) {
            return Metric.DataType.INT;
        }
        if (Boolean.class.equals(type)) {
            return Metric.DataType.BOOLEAN;
        }
        return Metric.DataType.STRING;
    }
}
