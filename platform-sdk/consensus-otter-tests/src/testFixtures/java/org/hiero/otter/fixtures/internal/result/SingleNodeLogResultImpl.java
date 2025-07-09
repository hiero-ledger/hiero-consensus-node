// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.Marker;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Default implementation of {@link SingleNodeLogResult}
 */
public class SingleNodeLogResultImpl implements SingleNodeLogResult {

    private final NodeId nodeId;
    private final Set<Marker> suppressedLogMarkers;

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    private volatile int startIndex = 0;

    /**
     * Creates a new instance of {@link SingleNodeLogResultImpl}.
     *
     * @param nodeId the {@link NodeId} of the node for which logs are collected
     * @param suppressedLogMarkers the set of {@link Marker} that should be ignored in the logs
     */
    public SingleNodeLogResultImpl(@NonNull final NodeId nodeId, @NonNull final Set<Marker> suppressedLogMarkers) {
        this.nodeId = requireNonNull(nodeId);
        this.suppressedLogMarkers = unmodifiableSet(requireNonNull(suppressedLogMarkers));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<StructuredLog> logs() {
        return InMemoryAppender.getLogs(nodeId).stream()
                .skip(startIndex)
                .filter(logEntry -> logEntry.marker() == null || !suppressedLogMarkers.contains(logEntry.marker()))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult suppressingLogMarker(@NonNull final LogMarker marker) {
        requireNonNull(marker, "marker cannot be null");

        final Set<Marker> markers = new HashSet<>(suppressedLogMarkers);
        markers.add(marker.getMarker());

        return new SingleNodeLogResultImpl(nodeId, markers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final LogSubscriber subscriber) {
        final LogSubscriber wrapper = logEntry -> {
            if (Objects.equals(this.nodeId, logEntry.nodeId()) && !suppressedLogMarkers.contains(logEntry.marker())) {
                return subscriber.onLogEntry(logEntry);
            }
            return SubscriberAction.CONTINUE;
        };
        InMemoryAppender.subscribe(wrapper);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        startIndex = InMemoryAppender.getLogs(nodeId).size();
    }
}
