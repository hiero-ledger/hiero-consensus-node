// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * A key for identifying a {@link Metric} by its name and class type.
 * Key instance is immutable and can be used to retrieve a metric from a {@link MetricRegistry}.
 * <p>
 * Callers may additionally categorize the metric by calling {@link #addCategory(String)}.
 * Metric name and category must not be blank and must only contain valid characters
 * defined by {@link MetricUtils#METRIC_NAME_REGEX}.
 */
public record MetricKey<M extends Metric>(
        @NonNull String name, @NonNull Class<M> type) {

    /**
     * Creates a new metric key instance with the specified name and type. <br>
     *
     * @param name the name of the metric, must not be blank
     * @param type the class type of the metric, must not be null
     * @throws NullPointerException    if name or type is {@code null}
     * @throws IllegalArgumentException if name doesn't match regex {@value MetricUtils#METRIC_NAME_REGEX}
     */
    public MetricKey {
        MetricUtils.validateMetricNameCharacters(name);
        Objects.requireNonNull(type, "metric type must not be null");
    }

    /**
     * Convenient factory method to construct metric key with generics. <br>
     * See {@link #MetricKey(String, Class)} for details.
     */
    @SuppressWarnings("unchecked")
    public static <M extends Metric> MetricKey<M> of(@NonNull String name, @NonNull Class<? super M> type) {
        return new MetricKey<>(name, (Class<M>) type);
    }

    /**
     * Returns a new metric key instance with the specified category prefixed to the metric name.
     * The category and name are separated by a colon (':').
     *
     * @param category the category to prefix to the metric name, must not be blank
     * @return a new metric key instance with the category prefixed to the name
     * @throws NullPointerException     if category is {@code null}
     * @throws IllegalArgumentException if category doesn't match regex {@value MetricUtils#METRIC_NAME_REGEX}
     */
    public MetricKey<M> addCategory(@NonNull String category) {
        MetricUtils.throwArgBlank(category, "category");
        return new MetricKey<>(category + ':' + name, type);
    }
}
