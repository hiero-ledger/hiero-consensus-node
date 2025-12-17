// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.Metric;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Represents a metrics event.
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 * @param type the type of the event
 * @param key the unique identifier (null for global metrics)
 * @param metric the metric
 */
public record MetricsEvent<KEY>(@NonNull Type type, @Nullable KEY key, @NonNull Metric metric) {
    public enum Type {
        ADDED,
        REMOVED
    }

    /**
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code type}</li>
     *       <li>{@code metric}</li>
     *     </ul>
     */
    public MetricsEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(metric, "metric must not be null");
    }
}
