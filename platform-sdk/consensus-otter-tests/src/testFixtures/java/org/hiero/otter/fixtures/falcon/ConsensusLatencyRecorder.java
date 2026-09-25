// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Collects the creation-to-consensus latency (C2C) and the event throughput of a Falcon network, in simulated time.
 *
 * <p>Every node of a Falcon network reports each of its consensus rounds to this recorder directly from the output of
 * its consensus engine. Rounds cannot be taken from the subscribers of the results collectors, because those receive
 * the instance interned in the {@link org.hiero.otter.fixtures.internal.result.ConsensusRoundPool}. The pool keeps only
 * one instance per round, carrying the {@link ConsensusRound#getReachedConsTimestamp() reached timestamp} of the first
 * node that reported it, so the timing of all other nodes would be lost.
 *
 * <p>The recorder is designed to be cheap enough to be always on. All nodes see the same events in a given round, so
 * the event-side aggregates of a round (number of events, sum and minimum of their creation times) are computed once,
 * by the first node that reports the round, and cached. Each node then contributes in constant time:
 * <ul>
 *     <li>sum of C2C of node {@code n} in round {@code r} = {@code count(r) * reached(n, r) - sumCreated(r)}</li>
 *     <li>max C2C of node {@code n} in round {@code r} = {@code reached(n, r) - minCreated(r)}</li>
 * </ul>
 * The cached aggregates of a round are evicted once every node has reported it, which keeps memory bounded.
 *
 * <p>All times are kept as nanoseconds relative to the moment the network was started, so that sums over millions of
 * events cannot overflow a {@code long}.
 *
 * <p>This class is not thread-safe. Falcon drives all nodes sequentially from a single thread, and its deterministic
 * wiring model runs all work on that thread.
 */
final class ConsensusLatencyRecorder {

    /**
     * The event-side aggregates of a consensus round, shared by all nodes.
     *
     * @param count the number of events in the round
     * @param sumCreatedNanos the sum of the creation times of all events, relative to the start
     * @param minCreatedNanos the earliest creation time of any event, relative to the start
     */
    private record RoundStats(int count, long sumCreatedNanos, long minCreatedNanos) {}

    /**
     * The C2C accumulated for a single node.
     */
    private static final class NodeStats {

        /** The number of events that reached consensus on this node. */
        private long events;

        /** The sum of the C2C of all those events, in nanoseconds. */
        private long sumC2cNanos;

        /** The largest C2C of any of those events, in nanoseconds. */
        private long maxC2cNanos;
    }

    /** The cached event-side aggregates of rounds that not every node has reported yet, by round number. */
    private final Map<Long, RoundStats> roundStats = new HashMap<>();

    /** The number of nodes that have reported each round in {@link #roundStats}, by round number. */
    private final Map<Long, Integer> roundReports = new HashMap<>();

    /** The C2C accumulated per node, in node registration order. */
    private final Map<NodeId, NodeStats> nodeStats = new LinkedHashMap<>();

    /** The simulated time at which the network was started, or {@code null} if it has not been started yet. */
    @Nullable
    private Instant start;

    /** The number of events created by all nodes. */
    private long createdEvents;

    /** The total serialized size of all events created by all nodes, in bytes. */
    private long createdBytes;

    /**
     * Registers a node of the network. Must be called for every node before the network is started, so that the
     * recorder knows when all nodes have reported a round.
     *
     * @param nodeId the ID of the node
     */
    void registerNode(@NonNull final NodeId nodeId) {
        nodeStats.putIfAbsent(requireNonNull(nodeId), new NodeStats());
    }

    /**
     * Marks the moment the network was started. Only the first call has an effect, so every node can call it when it
     * starts.
     *
     * @param now the current simulated time
     */
    void markStarted(@NonNull final Instant now) {
        if (start == null) {
            start = requireNonNull(now);
        }
    }

    /**
     * Records an event created by one of the nodes. Must be called exactly once per created event.
     *
     * @param event the newly created event
     */
    void onEventCreated(@NonNull final PlatformEvent event) {
        createdEvents++;
        createdBytes += GossipEvent.PROTOBUF.measureRecord(event.getGossipEvent());
    }

    /**
     * Records a consensus round reached by a node. Must be called with the node's own instance of the round, as
     * produced by its consensus engine, and not with the instance interned in the consensus round pool.
     *
     * @param nodeId the ID of the node that reached the round
     * @param round the round, as produced by the node's consensus engine
     * @throws IllegalStateException if the network has not been started yet
     * @throws IllegalArgumentException if the node has not been registered
     */
    void onConsensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {
        final NodeStats node = nodeStats.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node " + nodeId + " has not been registered");
        }

        final long roundNum = round.getRoundNum();
        final RoundStats stats = roundStats.computeIfAbsent(roundNum, _ -> computeStats(round));
        if (stats.count() > 0) {
            final long reachedNanos = relativeNanos(round.getReachedConsTimestamp());
            node.events += stats.count();
            node.sumC2cNanos += stats.count() * reachedNanos - stats.sumCreatedNanos();
            node.maxC2cNanos = Math.max(node.maxC2cNanos, reachedNanos - stats.minCreatedNanos());
        }

        // Evict once every node has reported the round, to keep memory bounded
        if (roundReports.merge(roundNum, 1, Integer::sum) >= nodeStats.size()) {
            roundReports.remove(roundNum);
            roundStats.remove(roundNum);
        }
    }

    /**
     * Creates a snapshot of everything recorded so far.
     *
     * @param now the current simulated time, which ends the measurement period
     * @return the snapshot
     * @throws IllegalStateException if the network has not been started yet
     */
    @NonNull
    ConsensusLatencyResult snapshot(@NonNull final Instant now) {
        final long elapsedNanos = relativeNanos(now);

        long totalEvents = 0;
        long totalC2cNanos = 0;
        long maxC2cNanos = 0;
        final Map<NodeId, Duration> averagePerNode = new LinkedHashMap<>();
        for (final Map.Entry<NodeId, NodeStats> entry : nodeStats.entrySet()) {
            final NodeStats node = entry.getValue();
            totalEvents += node.events;
            totalC2cNanos += node.sumC2cNanos;
            maxC2cNanos = Math.max(maxC2cNanos, node.maxC2cNanos);
            averagePerNode.put(entry.getKey(), average(node.sumC2cNanos, node.events));
        }

        return new ConsensusLatencyResult(
                nodeStats.size(),
                average(totalC2cNanos, totalEvents),
                Duration.ofNanos(maxC2cNanos),
                perSecond(createdEvents, elapsedNanos),
                perSecond(createdBytes, elapsedNanos),
                averagePerNode);
    }

    /**
     * Returns the number of rounds whose event-side aggregates are currently cached because not every node has reported
     * them yet. Visible for testing the eviction of cached rounds.
     *
     * @return the number of cached rounds
     */
    int cachedRounds() {
        return roundStats.size();
    }

    /**
     * Computes the event-side aggregates of a round.
     *
     * @param round the round
     * @return the aggregates
     */
    @NonNull
    private RoundStats computeStats(@NonNull final ConsensusRound round) {
        long sumCreatedNanos = 0;
        long minCreatedNanos = Long.MAX_VALUE;
        for (final PlatformEvent event : round.getConsensusEvents()) {
            final long createdNanos = relativeNanos(event.getTimeCreated());
            sumCreatedNanos += createdNanos;
            minCreatedNanos = Math.min(minCreatedNanos, createdNanos);
        }
        return new RoundStats(round.getNumEvents(), sumCreatedNanos, minCreatedNanos);
    }

    /**
     * Converts an instant to nanoseconds relative to the start of the network.
     *
     * @param instant the instant to convert
     * @return the nanoseconds since the start
     * @throws IllegalStateException if the network has not been started yet
     */
    private long relativeNanos(@NonNull final Instant instant) {
        if (start == null) {
            throw new IllegalStateException("The network has not been started yet");
        }
        return Duration.between(start, instant).toNanos();
    }

    /**
     * Computes an average duration.
     *
     * @param sumNanos the sum of all durations in nanoseconds
     * @param count the number of durations
     * @return the average, or {@link Duration#ZERO} if {@code count} is zero
     */
    @NonNull
    private static Duration average(final long sumNanos, final long count) {
        return count == 0 ? Duration.ZERO : Duration.ofNanos(sumNanos / count);
    }

    /**
     * Computes a rate per second of simulated time.
     *
     * @param amount the amount accumulated over the whole period
     * @param elapsedNanos the length of the period in nanoseconds
     * @return the amount per second, or {@code 0} if no time has elapsed
     */
    private static long perSecond(final long amount, final long elapsedNanos) {
        return elapsedNanos <= 0 ? 0 : (long) (amount * 1_000_000_000.0 / elapsedNanos);
    }
}
