// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
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
 *
 * <p>This class is not thread-safe. Results are added on one thread at a time and are never read while they are
 * added. Falcon and Container add the results of a node on the test thread. Turtle adds them on the thread that ticks
 * the node, and the test thread waits until all nodes have finished their tick before it continues.
 */
public class NodeResultsCollector {

    private final NodeId nodeId;
    // The rounds in the order the node delivered them. A restart delivers some of them again.
    private final List<ConsensusRound> consensusRounds = new ArrayList<>();
    private final List<ConsensusRoundSubscriber> consensusRoundSubscribers = new ArrayList<>();
    private final List<EventDescriptorWrapper> preConsensusEvents = new ArrayList<>();
    private final List<EventDescriptorWrapper> staleEvents = new ArrayList<>();
    private final List<PlatformStatus> platformStatuses = new ArrayList<>();
    private final List<PlatformStatusSubscriber> platformStatusSubscribers = new ArrayList<>();
    private final List<StructuredLog> logEntries = new ArrayList<>();
    private final List<LogSubscriber> logSubscribers = new ArrayList<>();
    private final ConsensusRoundPool roundPool;
    // Turtle and Container do not pass their pre-consensus and stale events yet (#28054, #28056)
    private final boolean collectsEvents;

    private boolean destroyed = false;

    /**
     * Creates a new instance of {@link NodeResultsCollector}.
     *
     * @param nodeId the node ID of the node
     * @param roundPool the shared pool for deduplicating consensus rounds across nodes
     * @param collectsEvents whether the node passes its pre-consensus and stale events to this collector
     */
    public NodeResultsCollector(
            @NonNull final NodeId nodeId, @NonNull final ConsensusRoundPool roundPool, final boolean collectsEvents) {
        this.nodeId = requireNonNull(nodeId, "nodeId should not be null");
        this.roundPool = requireNonNull(roundPool, "roundPool should not be null");
        this.collectsEvents = collectsEvents;
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
            final ConsensusRound canonicalRound = roundPool.canonicalize(round);
            consensusRounds.add(canonicalRound);

            consensusRoundSubscribers.removeIf(
                    subscriber -> subscriber.onConsensusRound(nodeId, canonicalRound) == SubscriberAction.UNSUBSCRIBE);
        }
    }

    /**
     * Adds an event that the node emitted for pre-consensus handling.
     *
     * @param event the descriptor of the pre-consensus event
     */
    public void addPreConsensusEvent(@NonNull final EventDescriptorWrapper event) {
        requireNonNull(event);
        if (!destroyed) {
            preConsensusEvents.add(event);
        }
    }

    /**
     * Adds an event that the node reported as stale.
     *
     * @param event the descriptor of the stale event
     */
    public void addStaleEvent(@NonNull final EventDescriptorWrapper event) {
        requireNonNull(event);
        if (!destroyed) {
            staleEvents.add(event);
        }
    }

    /**
     * Returns all the pre-consensus events emitted by this node until the moment of invocation, starting with and
     * including the provided index.
     *
     * @param startIndex the index to start from
     * @return the list of pre-consensus events
     * @throws UnsupportedOperationException if the node does not pass its pre-consensus events to this collector
     */
    @NonNull
    public List<EventDescriptorWrapper> currentPreConsensusEvents(final int startIndex) {
        throwIfEventsNotCollected();
        return List.copyOf(preConsensusEvents.subList(startIndex, preConsensusEvents.size()));
    }

    /**
     * Returns the number of pre-consensus events emitted by this node.
     *
     * @return the count of pre-consensus events
     */
    public int currentPreConsensusEventsCount() {
        return preConsensusEvents.size();
    }

    /**
     * Returns all the stale events reported by this node until the moment of invocation, starting with and including
     * the provided index.
     *
     * @param startIndex the index to start from
     * @return the list of stale events
     * @throws UnsupportedOperationException if the node does not pass its stale events to this collector
     */
    @NonNull
    public List<EventDescriptorWrapper> currentStaleEvents(final int startIndex) {
        throwIfEventsNotCollected();
        return List.copyOf(staleEvents.subList(startIndex, staleEvents.size()));
    }

    /**
     * Returns the number of stale events reported by this node.
     *
     * @return the count of stale events
     */
    public int currentStaleEventsCount() {
        return staleEvents.size();
    }

    private void throwIfEventsNotCollected() {
        if (!collectsEvents) {
            throw new UnsupportedOperationException(
                    "Pre-consensus and stale events are not collected in this environment yet.");
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
     * Returns the consensus rounds delivered by this node until the moment of invocation, starting with and including
     * the delivery at the provided index. A round that was delivered more than once is returned once, but different
     * rounds with the same round number are all returned. The rounds are ordered by round number.
     *
     * @param startIndex the index of the first delivery to include
     * @return the list of consensus rounds
     */
    @NonNull
    public List<ConsensusRound> currentConsensusRounds(final int startIndex) {
        // This creates a Set based on identity. The ConsensusRoundPool ensures that the same round is always the same
        // object. We cannot use a regular Set because equality of ConsensusRounds is based on the events only,
        // which means a regular Set would remove empty rounds that were emitted at different times.
        final Set<ConsensusRound> distinctRounds = Collections.newSetFromMap(new IdentityHashMap<>());
        distinctRounds.addAll(consensusRounds.subList(startIndex, consensusRounds.size()));
        return distinctRounds.stream()
                .sorted(comparing(ConsensusRound::getRoundNum))
                .toList();
    }

    /**
     * Returns the number of consensus rounds delivered by this node, counting a round once for every delivery.
     *
     * @return the count of delivered consensus rounds
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
