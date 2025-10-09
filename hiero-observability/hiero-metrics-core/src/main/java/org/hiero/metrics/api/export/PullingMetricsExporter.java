// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Supplier;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;

/**
 * Type of {@link MetricsExporter} that pulls metrics snapshots from
 * the {@link MetricsExportManager} on its own schedule.
 * <p>
 * Example: Prometheus scrapper.
 *
 * @see PushingMetricsExporter
 */
public interface PullingMetricsExporter extends MetricsExporter {

    /**
     * Initialize the exporter with a supplier of {@link MetricsSnapshot}.
     * The supplier can be called by the exporter when it needs to pull metrics data.
     *
     * @param snapshotSupplier the supplier of {@link MetricsSnapshot}
     */
    void init(@NonNull Supplier<Optional<MetricsSnapshot>> snapshotSupplier);
}
