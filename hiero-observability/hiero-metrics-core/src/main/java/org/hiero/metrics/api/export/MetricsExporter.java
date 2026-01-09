// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.util.function.Supplier;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

/**
 * Base interface for exporting metrics snapshots.
 * <p>
 * Implementations of this interface are responsible for exporting metrics data
 * provided by a {@link MetricsCollectionSnapshot} supplier.
 * Supplier is <b>synchronous</b> and no two snapshots can be taken at the same time.
 *
 * @see org.hiero.metrics.api.core.MetricRegistry
 * @see MetricsCollectionSnapshot
 */
public interface MetricsExporter extends Closeable {

    /**
     * Initialize the exporter with a supplier of {@link MetricsCollectionSnapshot}.
     * The supplier can be called by the exporter when it needs to pull metrics data.
     * Implementations should be able to handle multiple calls to this method.
     *
     * @param snapshotSupplier the supplier of {@link MetricsCollectionSnapshot}
     */
    void setSnapshotSupplier(@NonNull Supplier<MetricsCollectionSnapshot> snapshotSupplier);
}
