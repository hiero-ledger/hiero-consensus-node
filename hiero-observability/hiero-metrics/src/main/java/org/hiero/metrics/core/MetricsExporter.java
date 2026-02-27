// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.util.function.Supplier;

/**
 * Interface for exporting {@link MetricRegistry}.
 * <p>
 * Implementations of this interface are responsible for exporting metrics data
 * provided by a {@link MetricRegistrySnapshot} supplier.
 * Supplier is <b>synchronous</b> and no two snapshots can be taken at the same time.
 *
 * @see MetricRegistry
 * @see MetricRegistrySnapshot
 */
public interface MetricsExporter extends Closeable {

    /**
     * Initialize the exporter with a supplier of {@link MetricRegistrySnapshot}.
     * The supplier can be called by the exporter when it needs to pull metrics data.
     * Implementations should be able to handle multiple calls to this method.
     *
     * @param snapshotSupplier the supplier of {@link MetricRegistrySnapshot}
     */
    void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier);
}
