// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static com.swirlds.virtualmap.sync.LearnerSyncMetrics.RECONNECT_MAP_CATEGORY;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An immutable snapshot of the VirtualMap reconnect traversal metrics produced by one synchronization.
 *
 * <p>The transfer counters describe traversal-protocol work, not bytes. Different traversal algorithms may define one
 * transfer differently, so these values are useful for comparing the amount and shape of work performed under a fixed
 * algorithm. Values are keyed by the names registered in the metrics system, allowing new reconnect metrics to be
 * captured without changes to this benchmark class.
 *
 * @param values reconnect metric values keyed by their registered names
 */
public record ReconnectMapStatsSnapshot(Map<String, Long> values) {

    /**
     * Creates an immutable snapshot from the supplied values.
     *
     * @param values reconnect metric values keyed by their registered names
     * @throws NullPointerException if the map, a metric name, or a metric value is {@code null}
     */
    public ReconnectMapStatsSnapshot {
        values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    }

    /**
     * Captures all numeric metrics registered in the VirtualMap learner reconnect category.
     *
     * @param metrics registry containing the VirtualMap reconnect metrics
     * @return an immutable snapshot keyed by registered metric name
     * @throws NullPointerException if {@code metrics} is {@code null}
     * @throws IllegalStateException if the category contains no metrics or contains a non-numeric metric
     */
    public static ReconnectMapStatsSnapshot from(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        final Map<String, Long> values = new HashMap<>();
        for (final Metric metric : metrics.findMetricsByCategory(RECONNECT_MAP_CATEGORY)) {
            if (!RECONNECT_MAP_CATEGORY.equals(metric.getCategory())) {
                continue;
            }
            final Object value = metric.get(VALUE);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("Non-numeric reconnect metric " + metric.getIdentifier());
            }
            values.put(metric.getName(), number.longValue());
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("No metrics found in category " + RECONNECT_MAP_CATEGORY);
        }
        return new ReconnectMapStatsSnapshot(values);
    }

    /**
     * Formats all captured counters for the benchmark log.
     *
     * @return a single-line summary of the reconnect traversal metrics
     */
    public String format() {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; ", "ReconnectMapStatsSnapshot: ", ""));
    }
}
