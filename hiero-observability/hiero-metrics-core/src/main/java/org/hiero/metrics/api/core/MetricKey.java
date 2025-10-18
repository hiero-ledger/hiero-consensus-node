// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.metrics.api.utils.MetricUtils;

/**
 * A unique key for identifying a {@link Metric} by its name and type.
 * Key instance is immutable and can be used to retrieve a metric from a {@link MetricRegistry}.
 * <p>
 * Callers may additionally categorize the metric by calling {@link #withCategory(String)}.
 */
public final class MetricKey<M extends Metric> {

    private final String name;

    private final Class<M> type;

    private final int hashCode;

    /**
     * Constructs a new metric key instance with the specified name and type. <br>
     * Name must not be blank and must only contain valid characters
     * - see {@link MetricUtils#validateNameCharacters(String)}.
     *
     * @param name the name of the metric, must not be blank
     * @param type the class type of the metric, must not be null
     */
    private MetricKey(@NonNull String name, @NonNull Class<M> type) {
        this.name = name;
        this.type = Objects.requireNonNull(type, "metric type must not be null");

        hashCode = Objects.hash(this.name, this.type);
    }

    /**
     * Creates a new metric key instance with the specified name and type. <br>
     * Name must not be blank and must only contain valid characters
     * - see {@link MetricUtils#validateNameCharacters(String)}.
     *
     * @param name the name of the metric, must not be blank
     * @param type the class type of the metric, must not be null
     * @param <M>  the type of the metric
     * @return a new metric key instance
     */
    @SuppressWarnings("unchecked")
    public static <M extends Metric> MetricKey<M> of(@NonNull String name, @NonNull Class<? super M> type) {
        MetricUtils.validateNameCharacters(name);
        return new MetricKey<>(name, (Class<M>) type);
    }

    /**
     * Returns a new metric key instance with the specified category prefixed to the metric name.
     * The category and name are separated by a colon (':'). <br>
     * Category must not be blank and must only contain valid characters
     * - see {@link MetricUtils#validateNameCharacters(String)}.
     *
     * @param category the category to prefix to the metric name, must not be blank
     * @return a new metric key instance with the category prefixed to the name
     */
    public MetricKey<M> withCategory(@NonNull String category) {
        MetricUtils.validateNameCharacters(category);
        return new MetricKey<>(category + ':' + name, type);
    }

    @NonNull
    public String name() {
        return name;
    }

    @NonNull
    public Class<M> type() {
        return type;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MetricKey<?>) obj;
        return Objects.equals(this.name, that.name) && Objects.equals(this.type, that.type);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "MetricKey[" + "name=" + name + ", " + "type=" + type + ']';
    }
}
