// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform.prometheus;

import com.swirlds.metrics.api.snapshot.Snapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.prometheus.client.CollectorRegistry;

/**
 * Common interface of all adapters, which synchronize a {@link com.swirlds.metrics.api.Metric}
 * with a corresponding Prometheus {@link io.prometheus.client.Collector}.
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 */
public interface MetricAdapter<KEY> {

    /**
     * Update the {@link io.prometheus.client.Collector} with the data of the given snapshot.
     *
     * @param snapshot
     * 		The snapshot, which value should be used for the update.
     * @param key
     * 		The unique identifier in which context the metric is used. May be {@code null}, if it is a global metric.
     * @throws IllegalArgumentException if {@code snapshot} is {@code null}
     */
    void update(@NonNull Snapshot snapshot, @Nullable KEY key);

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
    void unregister(@NonNull CollectorRegistry registry);
}
