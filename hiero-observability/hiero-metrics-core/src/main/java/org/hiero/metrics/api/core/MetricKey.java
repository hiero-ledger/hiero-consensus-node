// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.metrics.api.utils.MetricUtils;

/**
 * A unique key for identifying a {@link Metric} by its name and type.
 * Key instance is immutable and can be used to retrieve a metric from a {@link MetricRegistry}.
 * <p>
 * Callers may additionally categorize the metric by calling {@link #withCategory(String)}.
 */
public record MetricKey<M extends Metric>(@NonNull String name, @NonNull Class<M> type) {

    /**
     * Creates a new metric key instance with the specified name and type. <br>
     * Name must not be blank and must only contain valid characters
     * - see {@link MetricUtils#validateMetricNameCharacters(String)}.
     *
     * @param name the name of the metric, must not be blank
     * @param type the class type of the metric, must not be null
     */
    public MetricKey {
        MetricUtils.validateMetricNameCharacters(name);
        Objects.requireNonNull(type, "metric type must not be null");
    }

    /**
     * Creates a new metric key instance with the specified name and type. <br>
     * Name must not be blank and must only contain valid characters
     * - see {@link MetricUtils#validateMetricNameCharacters(String)}.
     *
     * @param name the name of the metric, must not be blank
     * @param type the class type of the metric, must not be null
     * @param <M>  the type of the metric
     * @return a new metric key instance
     */
    @SuppressWarnings("unchecked")
    public static <M extends Metric> MetricKey<M> of(@NonNull String name, @NonNull Class<? super M> type) {
        return new MetricKey<>(name, (Class<M>) type);
    }

    /**
     * Returns a new metric key instance with the specified category prefixed to the metric name.
     * The category and name are separated by a colon (':'). <br>
     * Category must not be blank and must only contain valid characters
     * - see {@link MetricUtils#validateMetricNameCharacters(String)}.
     *
     * @param category the category to prefix to the metric name, must not be blank
     * @return a new metric key instance with the category prefixed to the name
     */
    public MetricKey<M> withCategory(@NonNull String category) {
        ArgumentUtils.throwArgBlank(category, "category");
        return new MetricKey<>(category + ':' + name, type);
    }
}
