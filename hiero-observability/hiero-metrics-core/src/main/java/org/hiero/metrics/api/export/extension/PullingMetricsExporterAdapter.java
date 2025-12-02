// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.hiero.metrics.api.export.AbstractMetricsExporter;
import org.hiero.metrics.api.export.PullingMetricsExporter;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;

/**
 * Base class for {@link PullingMetricsExporter} implementations.
 * It provides a mechanism to supply metrics snapshots via a {@link Supplier}.
 */
public class PullingMetricsExporterAdapter extends AbstractMetricsExporter implements PullingMetricsExporter {

    private volatile Supplier<Optional<MetricsCollectionSnapshot>> snapshotSupplier = Optional::empty;

    public PullingMetricsExporterAdapter(@NonNull String name) {
        super(name);
    }

    @Override
    public final void setSnapshotProvider(@NonNull Supplier<Optional<MetricsCollectionSnapshot>> snapshotSupplier) {
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier);
    }

    /**
     * @return an {@link Optional} containing the latest {@link MetricsCollectionSnapshot} if available,
     *         or an empty {@link Optional} if no snapshot is available.
     */
    @NonNull
    public final Optional<MetricsCollectionSnapshot> getSnapshot() {
        return snapshotSupplier.get();
    }

    @Override
    public void close() throws IOException {
        snapshotSupplier = Optional::empty;
    }
}
