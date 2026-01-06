// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Snapshot of a single measurement of a {@link org.hiero.metrics.api.core.Metric} at some point in time.
 * Implementations are mutable and reusable for performance reasons, allowing to update the measurement snapshot
 * in place with centralized snapshotting manager. Measurement snapshot can be used as key in hash map
 * to cache some specific to export destination measurement representation or template (like bytes array).
 * <p>
 * Exporters must cast to specific implementation classes to access additional data
 * beyond the {@link MeasurementSnapshot} interface. Existing extensions are:
 * <ul>
 *     <li> {@link LongValueMeasurementSnapshot}
 *     <li> {@link DoubleValueMeasurementSnapshot}
 * </ul>
 *
 * @see MetricSnapshot
 */
public interface MeasurementSnapshot {

    /**
     * Returns the value of the dynamic label at the given index.
     * The index corresponds to {@link MetricSnapshot#dynamicLabelNames()} list indexing.
     *
     * @param idx the index of the dynamic label
     * @return the value of the dynamic label at the given index
     */
    @NonNull
    String labelValue(int idx);
}
