// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.snapshot.Snapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.Objects;

/**
 * Represents a snapshot event that contains a collection of snapshots.
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 * @param key the unique identifier (null for global metrics)
 * @param snapshots the collection of snapshots
 */
public record SnapshotEvent<KEY>(@Nullable KEY key, @NonNull Collection<Snapshot> snapshots) {

    /**
     * @throws NullPointerException in case {@code snapshots} parameter is {@code null}
     */
    public SnapshotEvent {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
    }
}
