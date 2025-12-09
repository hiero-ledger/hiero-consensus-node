// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricSnapshot;

/**
 * A metric that can produce a snapshot of its current measurements.
 */
public interface SnapshotableMetric<S extends MeasurementSnapshot> extends Metric {

    UpdatableMetricSnapshot<?, S> snapshot();
}
