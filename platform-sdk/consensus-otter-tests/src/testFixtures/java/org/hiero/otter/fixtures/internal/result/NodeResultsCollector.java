// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.ConsensusRoundSubscriber;
import org.hiero.otter.fixtures.result.LogSubscriber;
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
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new CopyOnWriteArrayList<>();
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();
    private final List<PlatformStatusSubscriber> platformStatusSubscribers = new CopyOnWriteArrayList<>();
    private final List<StructuredLog> logEntries = new ArrayList<>();
    private final List<LogSubscriber> logSubscribers = new CopyOnWriteArrayList<>();
    private final ConsensusRoundPool roundPool;

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    private volatile boolean destroyed = false;

    /**
     * Creates a new instance of {@link NodeResultsCollector}.
     *
     * @param nodeId the node ID of the node
     * @param roundPool the shared pool for deduplicating consensus rounds across nodes
     */
    public NodeResultsCollector(@NonNull final NodeId nodeId, @NonNull final ConsensusRoundPool roundPool) {
        this.nodeId = requireNonNull(nodeId, "nodeId should not be null");
        this.roundPool = requireNonNull(roundPool, "roundPool should not be null");
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
     * Rounds are deduplicated through the shared pool before being stored.
     *
     * @param round the consensus round to add
     */
    public void addConsensusRound(@NonNull final ConsensusRound round) {
        requireNonNull(round);
        if (!destroyed) {
            // sends each round through the pool to deduplicate across nodes
            final ConsensusRound internalRound = roundPool.update(round, nodeId);

            consensusRoundSubscribers.removeIf(
                    subscriber -> subscriber.onConsensusRound(nodeId, internalRound) == SubscriberAction.UNSUBSCRIBE);
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
     * Returns all the consensus rounds created at the moment of invocation, starting with and including the provided
     * index.
     *
     * @param startIndex the index to start from
     * @return the list of consensus rounds
     */
    @NonNull
    public List<ConsensusRound> currentConsensusRounds(final int startIndex) {
        return roundPool.currentConsensusRounds(startIndex, nodeId);
    }

    /**
     * Returns the number of consensus rounds created by this node.
     *
     * @return the count of consensus rounds
     */
    public int currentConsensusRoundsCount() {
        return roundPool.size(nodeId);
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
