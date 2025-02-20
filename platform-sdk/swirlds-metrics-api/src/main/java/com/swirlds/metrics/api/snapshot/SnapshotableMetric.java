// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api.snapshot;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A metric that can provide a snapshot of its current values.
 */
public interface SnapshotableMetric extends Metric {

    /**
     * Take entries of the current values and return them. If the functionality of this {@code PlatformMetric} requires
     * it to be reset in regular intervals, it is done automatically after the snapshot was generated. The list of
     * {@code ValueTypes} will always be in the same order.
     *
     * @return the list of {@code ValueTypes} with their current values
     */
    @NonNull
    List<SnapshotEntry> takeSnapshot();
}
