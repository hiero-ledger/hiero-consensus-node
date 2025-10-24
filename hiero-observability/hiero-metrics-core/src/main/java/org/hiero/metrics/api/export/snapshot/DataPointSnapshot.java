// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Snapshot of a single data point of a {@link org.hiero.metrics.api.core.Metric} at some point in time.
 * Implementations are mutable and reusable for performance reasons, allowing to update the data point snapshot
 * in place with centralized snapshotting manager. Data point snapshot can be used as key in hash map
 * to cache some specific to export destination data point representation or template (like bytes array).
 * <p>
 * Exporters must cast to specific implementation classes to access additional data
 * beyond the {@link DataPointSnapshot} interface. Existing extensions are:
 * <ul>
 *     <li> {@link OneValueDataPointSnapshot}
 *     <li> {@link MultiValueDataPointSnapshot}
 *     <li> {@link StateSetDataPointSnapshot}
 * </ul>
 *
 * @see MetricSnapshot
 */
public interface DataPointSnapshot {

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
