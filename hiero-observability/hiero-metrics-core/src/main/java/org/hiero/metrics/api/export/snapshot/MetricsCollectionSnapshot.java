// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.metrics.api.core.ArrayAccessor;

/**
 * Snapshot of metrics at some point in time.<br>
 * Extends {@link Iterable} over {@link MetricSnapshot}.
 */
public interface MetricsCollectionSnapshot extends ArrayAccessor<MetricSnapshot> {

    /**
     * @return timestamp at which snapshot is created, never {@code null}
     */
    @NonNull
    Instant createAt();
}
