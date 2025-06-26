// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Implementation of {@link SingleNodeLogResult} that stores the log results for a single node.
 *
 * @param nodeId the ID of the node
 * @param logs the list of log entries for the node
 */
public record SingleNodeLogResultImpl(@NonNull NodeId nodeId, @NonNull List<StructuredLog> logs)
        implements SingleNodeLogResult {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult suppressingLogMarker(@NonNull final LogMarker marker) {
        Objects.requireNonNull(marker, "marker cannot be null");

        if (markers().contains(marker.getMarker())) {
            final List<StructuredLog> filteredLogs = logs.stream()
                    .filter(msg -> Objects.equals(msg.marker(), marker.getMarker()))
                    .toList();

            return new SingleNodeLogResultImpl(nodeId, filteredLogs);
        }

        return this;
    }
}
