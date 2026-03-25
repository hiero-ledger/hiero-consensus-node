// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.sloth.fixtures.logging.StructuredLog;
import org.hiero.sloth.fixtures.result.LogSubscriber;
import org.hiero.sloth.fixtures.result.PlatformStatusSubscriber;
import org.hiero.sloth.fixtures.result.SingleNodeLogResult;
import org.hiero.sloth.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.sloth.fixtures.result.SubscriberAction;

/**
 * Helper class that collects all test results of a node.
 */
public class NodeResultsCollector {

    private final NodeId nodeId;
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();
    private final List<PlatformStatusSubscriber> platformStatusSubscribers = new CopyOnWriteArrayList<>();
    private final List<StructuredLog> logEntries = new ArrayList<>();
    private final List<LogSubscriber> logSubscribers = new CopyOnWriteArrayList<>();

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    private volatile boolean destroyed = false;

    /**
     * Creates a new instance of {@link NodeResultsCollector}.
     *
     * @param nodeId the node ID of the node
     */
    public NodeResultsCollector(@NonNull final NodeId nodeId) {
        this.nodeId = requireNonNull(nodeId, "nodeId should not be null");
    }

    /**
     * Returns the node ID of the node that created the results.
     *
     * @return the node ID
     */
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * Adds a {@link PlatformStatus} to the list of collected statuses.
     *
     * @param status the {@link PlatformStatus} to add
     */
    public void addPlatformStatus(@NonNull final PlatformStatus status) {
        requireNonNull(status);
        if (!destroyed) {
            platformStatuses.add(status);
            platformStatusSubscribers.removeIf(
                    subscriber -> subscriber.onPlatformStatusChange(nodeId, status) == SubscriberAction.UNSUBSCRIBE);
        }
    }

    /**
     * Adds a log entry to the list of collected logs.
     *
     * @param logEntry the {@link StructuredLog} to add
     */
    public void addLogEntry(@NonNull final StructuredLog logEntry) {
        requireNonNull(logEntry);
        if (!destroyed) {
            logEntries.add(logEntry);
            logSubscribers.removeIf(subscriber -> subscriber.onLogEntry(logEntry) == SubscriberAction.UNSUBSCRIBE);
        }
    }

    /**
     * Returns a {@link SingleNodePlatformStatusResult} of the current state.
     *
     * @return the {@link SingleNodePlatformStatusResult}
     */
    @NonNull
    public SingleNodePlatformStatusResult newStatusProgression() {
        return new SingleNodePlatformStatusResultImpl(this);
    }

    /**
     * Returns all the platform statuses the node went through until the moment of invocation, starting with and
     * including the provided index.
     *
     * @param startIndex the index to start from
     * @return the list of platform statuses
     */
    @NonNull
    public List<PlatformStatus> currentStatusProgression(final int startIndex) {
        final List<PlatformStatus> copy = List.copyOf(platformStatuses);
        return copy.subList(startIndex, copy.size());
    }

    /**
     * Returns the number of platform statuses created by this node.
     *
     * @return the count of platform statuses
     */
    public int currentStatusProgressionCount() {
        return platformStatuses.size();
    }

    /**
     * Subscribes to {@link PlatformStatus} events.
     *
     * @param subscriber the subscriber that will receive the platform statuses
     */
    public void subscribePlatformStatusSubscriber(@NonNull final PlatformStatusSubscriber subscriber) {
        requireNonNull(subscriber);
        platformStatusSubscribers.add(subscriber);
    }

    /**
     * Returns a new {@link SingleNodeLogResult} for the node.
     *
     * @return the new {@link SingleNodeLogResult}
     */
    @NonNull
    public SingleNodeLogResult newLogResult() {
        return new SingleNodeLogResultImpl(this, Set.of(), Set.of());
    }

    /**
     * Returns all the log entries created at the moment of invocation, starting with and including the provided index.
     *
     * @param startIndex the index to start from
     * @param suppressedLogMarkers the set of {@link Marker} that should be ignored in the logs
     * @param suppressedLoggerNames the set of logger names that should be ignored in the logs
     * @return the list of log entries
     */
    @NonNull
    public List<StructuredLog> currentLogEntries(
            final long startIndex,
            @NonNull final Set<Marker> suppressedLogMarkers,
            @NonNull final Set<String> suppressedLoggerNames) {
        return logEntries.stream()
                .skip(startIndex)
                .filter(logEntry -> logEntry.marker() == null || !suppressedLogMarkers.contains(logEntry.marker()))
                .filter(logEntry -> !suppressedLoggerNames.contains(logEntry.loggerName()))
                .toList();
    }

    /**
     * Returns the number of log entries created by this node.
     *
     * @return the count of log entries
     */
    public int currentLogEntriesCount() {
        return logEntries.size();
    }

    /**
     * Subscribes to log events for the node.
     *
     * @param subscriber the subscriber that will receive log events
     */
    public void subscribeLogSubscriber(@NonNull final LogSubscriber subscriber) {
        requireNonNull(subscriber);
        logSubscribers.add(subscriber);
    }

    /**
     * Destroys the collector and prevents any further updates.
     */
    public void destroy() {
        destroyed = true;
    }
}
