// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import com.swirlds.metrics.api.snapshot.Snapshot;
import io.prometheus.client.CollectorRegistry;
import org.hiero.consensus.model.node.NodeId;

/**
 * Common interface of all adapters, which synchronize a {@link com.swirlds.metrics.api.Metric}
 * with a corresponding Prometheus {@link io.prometheus.client.Collector}.
 */
public interface MetricAdapter {

    /**
     * Update the {@link io.prometheus.client.Collector} with the data of the given snapshot.
     *
     * @param snapshot
     * 		The snapshot, which value should be used for the update.
     * @param nodeId
     * 		The {@link NodeId} in which context the metric is used. May be {@code null}, if it is a global metric.
     * @throws IllegalArgumentException if {@code snapshot} is {@code null}
     */
    void update(final Snapshot snapshot, final NodeId nodeId);

    /**
     * Increase the reference count
     *
     * @return the new reference count
     */
    int incAndGetReferenceCount();

    /**
     * Decrease the reference count
     *
     * @return the new reference count
     */
    int decAndGetReferenceCount();

    /**
     * Unregister all created Prometheus metrics
     *
     * @param registry
     * 		The {@link CollectorRegistry} from which to unregister
     */
    void unregister(final CollectorRegistry registry);
}
