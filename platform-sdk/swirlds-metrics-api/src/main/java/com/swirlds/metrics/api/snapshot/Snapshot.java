// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api.snapshot;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import java.util.List;
import java.util.Objects;

/**
 * An instance of {@code Snapshot} contains the data of a single snapshot of a {@link Metric}.
 */
public record Snapshot(Metric metric, List<SnapshotEntry> entries) {

    /**
     * Create a {@code Snapshot} of a {@link SnapshotableMetric}
     *
     * @param metric The source metric
     * @return the {@code Snapshot}
     * @throws NullPointerException in case {@code metric} parameter is {@code null}
     */
    public static Snapshot of(final SnapshotableMetric metric) {
        Objects.requireNonNull(metric, "metric must not be null");
        return new Snapshot(metric, metric.takeSnapshot());
    }

    /**
     * Get the main value of this {@code Snapshot}
     *
     * @return the main value
     */
    public Object getValue() {
        for (final SnapshotEntry entry : entries) {
            if (entry.valueType == ValueType.VALUE) {
                return entry.value;
            }
        }
        throw new IllegalStateException("Snapshot does not contain a value: " + this);
    }

    /**
     * As single entry within a {@code Snapshot}
     *
     * @param valueType the {@link ValueType} of this entry
     * @param value     the actual value
     */
    public record SnapshotEntry(ValueType valueType, Object value) {}
}
