// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Supplier;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

/**
 * Type of {@link MetricsExporter} that pulls metrics snapshots from
 * the {@link MetricsExportManager} on its own schedule.
 * <p>
 * Example: Prometheus scrapper.
 *
 * @see PushingMetricsExporter
 */
public non-sealed interface PullingMetricsExporter extends MetricsExporter {

    /**
     * Initialize the exporter with a supplier of {@link MetricsCollectionSnapshot}.
     * The supplier can be called by the exporter when it needs to pull metrics data.
     * Implementations should be able to handle multiple calls to this method.
     *
     * @param snapshotSupplier the supplier of {@link MetricsCollectionSnapshot}
     */
    void setSnapshotProvider(@NonNull Supplier<Optional<MetricsCollectionSnapshot>> snapshotSupplier);
}
