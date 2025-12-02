// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

/**
 * Type of {@link MetricsExporter} that receives metrics snapshots pushed to it
 * by the {@link MetricsExportManager} on regular intervals.
 * <p>
 * Example: CSV file exporter.
 *
 * @see PullingMetricsExporter
 */
public non-sealed interface PushingMetricsExporter extends MetricsExporter {

    /**
     * Export the given metrics snapshot to the destination.
     *
     * @param snapshot metrics snapshot to export
     * @throws MetricsExportException if error happens during export
     */
    void export(@NonNull MetricsCollectionSnapshot snapshot) throws MetricsExportException;
}
