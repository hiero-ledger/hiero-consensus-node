// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.MarkerFileSubscriber;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * Helper class that collects all test results of a node.
 */
public class NodeResultsCollector {

    private final NodeId nodeId;
    private final Queue<ConsensusRound> consensusRounds = new ConcurrentLinkedQueue<>();
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new CopyOnWriteArrayList<>();
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();
    private final List<PlatformStatusSubscriber> platformStatusSubscribers = new CopyOnWriteArrayList<>();
    private final List<StructuredLog> logEntries = new ArrayList<>();
    private final List<LogSubscriber> logSubscribers = new CopyOnWriteArrayList<>();
    private final List<MarkerFileSubscriber> markerFileSubscribers = new CopyOnWriteArrayList<>();

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    private volatile boolean destroyed = false;
    private volatile MarkerFilesStatus markerFilesStatus = MarkerFilesStatus.INITIAL_STATUS;

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
     * Adds a consensus round to the list of rounds created during the test.
     *
     * @param rounds the consensus rounds to add
     */
    public void addConsensusRounds(@NonNull final List<ConsensusRound> rounds) {
        requireNonNull(rounds);
        if (!destroyed) {
            consensusRounds.addAll(rounds);
            consensusRoundSubscribers.removeIf(
                    subscriber -> subscriber.onConsensusRounds(nodeId, rounds) == SubscriberAction.UNSUBSCRIBE);
        }
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
     * Returns a {@link SingleNodeConsensusResult} of the current state.
     *
     * @return the {@link SingleNodeConsensusResult}
     */
    @NonNull
    public SingleNodeConsensusResult newConsensusResult() {
        return new SingleNodeConsensusResultImpl(this);
    }

    /**
     * Returns all the consensus rounds created at the moment of invocation, starting with and including the provided index.
     *
     * @param startIndex the index to start from
     * @return the list of consensus rounds
     */
    @NonNull
    public List<ConsensusRound> currentConsensusRounds(final int startIndex) {
        final List<ConsensusRound> copy = List.copyOf(consensusRounds);
        return copy.subList(startIndex, copy.size());
    }

    /**
     * Returns the number of consensus rounds created by this node.
     *
     * @return the count of consensus rounds
     */
    public int currentConsensusRoundsCount() {
        return consensusRounds.size();
    }

    /**
     * Subscribes to {@link ConsensusRound}s created by this node.
     *
     * @param subscriber the subscriber that will receive the rounds
     */
    public void subscribeConsensusRoundSubscriber(@NonNull final ConsensusRoundSubscriber subscriber) {
        requireNonNull(subscriber);
        consensusRoundSubscribers.add(subscriber);
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
     * Returns all the platform statuses the node went through until the moment of invocation, starting with and including the provided index.
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
        return new SingleNodeLogResultImpl(this, Set.of());
    }

    /**
     * Returns all the log entries created at the moment of invocation, starting with and including the provided index.
     *
     * @param startIndex the index to start from
     * @param suppressedLogMarkers the set of {@link Marker} that should be ignored in the logs
     * @return the list of log entries
     */
    @NonNull
    public List<StructuredLog> currentLogEntries(
            final long startIndex, @NonNull final Set<Marker> suppressedLogMarkers) {
        return logEntries.stream()
                .skip(startIndex)
                .filter(logEntry -> logEntry.marker() == null || !suppressedLogMarkers.contains(logEntry.marker()))
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
     * Add new marker files to the collector.
     *
     * @param markerFileNames the names of the new marker files
     */
    public void addMarkerFiles(@NonNull final List<String> markerFileNames) {
        requireNonNull(markerFilesStatus);
        if (!destroyed && !markerFileNames.isEmpty()) {
            this.markerFilesStatus = markerFilesStatus.withMarkerFiles(markerFileNames);
            markerFileSubscribers.removeIf(subscriber ->
                    subscriber.onNewMarkerFile(nodeId, markerFilesStatus) == SubscriberAction.UNSUBSCRIBE);
        }
    }

    /**
     * Returns the current status of marker files for the node.
     *
     * @return the current status of marker files
     */
    public MarkerFilesStatus markerFilesStatus() {
        return markerFilesStatus;
    }

    /**
     * Subscribes to marker file updates for the node.
     *
     * @param subscriber the subscriber that will receive updates about marker files
     */
    public void subscribeMarkerFileSubscriber(@NonNull final MarkerFileSubscriber subscriber) {
        requireNonNull(subscriber);
        markerFileSubscribers.add(subscriber);
    }

    /**
     * Destroys the collector and prevents any further updates.
     */
    public void destroy() {
        destroyed = true;
    }
}
