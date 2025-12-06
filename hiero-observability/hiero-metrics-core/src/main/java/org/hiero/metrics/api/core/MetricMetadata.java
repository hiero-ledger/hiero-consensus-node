// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Immutable metadata of a {@link Metric}, including its type, name, description, and unit.
 * <p>
 * Description and unit can be {@code null}, in which case they default to an empty strings.
 */
public record MetricMetadata(
        @NonNull MetricType metricType, @NonNull String name, @NonNull String description, @NonNull String unit) {

    private static final String EMPTY = "";

    /**
     * Constructs a new MetricMetadata instance with the specified properties.
     *
     * @param metricType  the type of the metric, must not be {@code null}
     * @param name        the name of the metric, must not be blank
     * @param description an optional description of the metric, can be {@code null}
     * @param unit        an optional unit of measurement for the metric, can be {@code null}
     */
    public MetricMetadata(
            @NonNull MetricType metricType, @NonNull String name, @Nullable String description, @Nullable String unit) {
        this.metricType = Objects.requireNonNull(metricType, "metric type must not be null");
        this.name = ArgumentUtils.throwArgBlank(name, "name");
        this.description = description == null ? EMPTY : description;
        this.unit = unit == null ? EMPTY : unit;
    }
}
