// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;

/**
 * Base interface for metrics exporters.
 * <p>
 * Metrics exporter able to handle {@link MetricsSnapshot} into specific destination.
 * It also extends {@link Closeable} to allow proper resource management.
 *
 * @see PullingMetricsExporter
 * @see PushingMetricsExporter
 * @see MetricsSnapshot
 */
public sealed interface MetricsExporter extends Closeable
        permits PullingMetricsExporter, PushingMetricsExporter, AbstractMetricsExporter {

    /**
     * Default implementations returns the simple class name as the exporter name.
     *
     * @return the name of the exporter, never {@code null} or blank
     */
    @NonNull
    default String name() {
        return getClass().getSimpleName();
    }
}
