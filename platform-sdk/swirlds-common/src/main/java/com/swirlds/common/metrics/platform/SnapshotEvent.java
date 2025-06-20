// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.snapshot.Snapshot;
import java.util.Collection;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;

/**
 * Represents a snapshot event that contains a collection of snapshots.
 * @param nodeId the node identifier
 * @param snapshots the collection of snapshots
 */
public record SnapshotEvent(NodeId nodeId, Collection<Snapshot> snapshots) {

    /**
     * @throws NullPointerException in case {@code snapshots} parameter is {@code null}
     */
    public SnapshotEvent {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
    }
}
