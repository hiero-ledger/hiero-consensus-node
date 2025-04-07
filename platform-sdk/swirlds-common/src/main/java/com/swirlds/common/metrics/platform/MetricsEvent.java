// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.Metric;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

public record MetricsEvent(Type type, NodeId nodeId, Metric metric) {
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
