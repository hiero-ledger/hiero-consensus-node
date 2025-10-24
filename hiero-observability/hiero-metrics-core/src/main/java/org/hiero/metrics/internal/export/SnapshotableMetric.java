// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricSnapshot;

/**
 * A metric that can produce a snapshot of its current data points.
 */
public interface SnapshotableMetric<S extends DataPointSnapshot> extends Metric {

    UpdatableMetricSnapshot<?, S> snapshot();
}
