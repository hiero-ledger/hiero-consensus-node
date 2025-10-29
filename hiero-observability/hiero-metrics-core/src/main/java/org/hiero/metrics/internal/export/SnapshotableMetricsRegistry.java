// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricRegistrySnapshot;

/**
 * An extension of {@link MetricRegistry} that provides a method to take snapshots of all registered
 * metrics that implement {@link SnapshotableMetric}.
 */
public sealed interface SnapshotableMetricsRegistry extends MetricRegistry
        permits org.hiero.metrics.internal.core.MetricRegistryImpl {

    @NonNull
    UpdatableMetricRegistrySnapshot snapshot();
}
