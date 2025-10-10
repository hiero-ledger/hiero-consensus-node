// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;

/**
 * Base interface for metrics exporters.
 * <p>
 * Metrics exporter able to handle {@link MetricsSnapshot} into specific destination.
 * Implementations expected to be synchronous to properly re-use snapshots.
 *
 * @see PullingMetricsExporter
 * @see PushingMetricsExporter
 * @see MetricsSnapshot
 */
public interface MetricsExporter extends Closeable {

    /**
     * @return the name of the exporter, never {@code null} or blank
     */
    @NonNull
    String name();
}
