// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.metrics.api.core.ArrayAccessor;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricMetadata;

/**
 * Snapshot of the {@link org.hiero.metrics.api.core.Metric} and its measurements at some point in time.
 * Implementations are be mutable and reusable for performance reasons, allowing to update the measurement snapshots
 * in place with centralized snapshotting manager. Metric snapshot can be used as key in hash map
 * to cache some specific to export destination metric representation or template (like bytes array).
 *
 * @see MeasurementSnapshot
 */
public interface MetricSnapshot extends ArrayAccessor<MeasurementSnapshot> {

    /**
     * @return metric metadata, never {@code null}
     */
    @NonNull
    MetricMetadata metadata();

    /**
     * @return list of static labels associated with metric, could be emtpy but never {@code null}
     */
    @NonNull
    List<Label> staticLabels();

    /**
     * @return list of dynamic label names associated with metric, could be emtpy but never {@code null}
     */
    @NonNull
    List<String> dynamicLabelNames();
}
