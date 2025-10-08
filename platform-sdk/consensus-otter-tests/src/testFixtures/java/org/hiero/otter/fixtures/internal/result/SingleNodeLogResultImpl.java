// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Default implementation of {@link SingleNodeLogResult}
 */
public class SingleNodeLogResultImpl implements SingleNodeLogResult {

    private final NodeResultsCollector collector;
    private final Set<Marker> suppressedLogMarkers;
    private final Set<String> suppressedLoggerNames;

    // This class may be used in a multithreaded context, so we use volatile to ensure visibility of state changes
    private volatile long startIndex = 0;

    /**
     * Creates a new instance of {@link SingleNodeLogResultImpl}.
     *
     * @param collector the {@link NodeResultsCollector} that collects the results
     * @param suppressedLogMarkers the set of {@link Marker} that should be ignored in the logs
     */
    public SingleNodeLogResultImpl(
            @NonNull final NodeResultsCollector collector,
            @NonNull final Set<Marker> suppressedLogMarkers,
            @NonNull final Set<String> suppressedLoggerNames) {
        this.collector = requireNonNull(collector);
        this.suppressedLogMarkers = Set.copyOf(suppressedLogMarkers);
        this.suppressedLoggerNames = Set.copyOf(suppressedLoggerNames);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public NodeId nodeId() {
        return collector.nodeId();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<StructuredLog> logs() {
        return collector.currentLogEntries(startIndex, suppressedLogMarkers, suppressedLoggerNames);
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

        return new SingleNodeLogResultImpl(collector, markers, suppressedLoggerNames);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult suppressingLoggerName(@NonNull final Class<?> clazz) {
        requireNonNull(clazz, "clazz cannot be null");
        return suppressingLoggerName(clazz.getCanonicalName());
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SingleNodeLogResult suppressingLoggerName(@NonNull final String loggerName) {
        requireNonNull(loggerName, "loggerName cannot be null");

        final Set<String> loggerNames = new HashSet<>(suppressedLoggerNames);
        loggerNames.add(loggerName);

        return new SingleNodeLogResultImpl(collector, suppressedLogMarkers, loggerNames);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NonNull final LogSubscriber subscriber) {
        final LogSubscriber wrapper = logEntry -> {
            if (!isMarkerSuppressed(logEntry) && !isLoggerNameSuppressed(logEntry)) {
                return subscriber.onLogEntry(logEntry);
            }
            return SubscriberAction.CONTINUE;
        };
        collector.subscribeLogSubscriber(wrapper);
    }

    private boolean isMarkerSuppressed(@NonNull final StructuredLog logEntry) {
        return logEntry.marker() != null && suppressedLogMarkers.contains(logEntry.marker());
    }

    private boolean isLoggerNameSuppressed(@NonNull final StructuredLog logEntry) {
        return suppressedLoggerNames.contains(logEntry.loggerName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        startIndex = collector.currentLogEntriesCount();
    }
}
