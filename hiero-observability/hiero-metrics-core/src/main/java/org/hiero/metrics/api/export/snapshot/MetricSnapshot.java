// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import org.hiero.metrics.api.core.ArrayAccessor;
import org.hiero.metrics.api.core.MetricInfo;

/**
 * Snapshot of the {@link org.hiero.metrics.api.core.Metric} and its measurements at some point in time.
 * Implementations are be mutable and reusable for performance reasons, allowing to update the measurement snapshots
 * in place with centralized snapshotting manager. Metric snapshot can be used as key in hash map
 * to cache some specific to export destination metric representation or template (like bytes array).
 *
 * @see MeasurementSnapshot
 */
public interface MetricSnapshot extends MetricInfo, ArrayAccessor<MeasurementSnapshot> {}
