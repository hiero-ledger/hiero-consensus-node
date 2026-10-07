// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
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
 * <p>Recording a value takes a few nanoseconds and never allocates. Every node records the events of its own rounds,
 * which is the same order of work as the node's consensus engine does for them, so the recorder is cheap enough to be
 * always on. Each node owns a histogram of {@link #PRECISION_BITS} precision bits and {@link #RANGE_BITS} range bits,
 * which takes about 216 KB.
 *
 * <p>All times are kept as nanoseconds relative to the moment the network was started.
 *
 * <p>This class is not thread-safe, just like {@link LatencyHistogram}. Falcon drives all nodes sequentially from a
 * single thread, and its deterministic wiring model runs all work on that thread, so the nodes can share the recorder
 * without synchronization.
 */
final class ConsensusLatencyRecorder {

    /** The number of precision bits of the histograms: buckets at most 2^-10 (about 0.1%) of their values wide. */
    static final int PRECISION_BITS = 10;

    /** The number of range bits of the histograms: latencies up to 2^36 - 1 ns (about 68.7 s) are tracked. */
    static final int RANGE_BITS = 36;

    /** The number of nanoseconds in a second. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** The C2C observed by each node, in node registration order. */
    private final Map<NodeId, LatencyHistogram> nodeHistograms = new LinkedHashMap<>();

    /** The simulated time at which the network was started, or {@code null} if it has not been started yet. */
    @Nullable
    private Instant start;

    /** The number of events created by all nodes. */
    private long createdEvents;

    /** The total serialized size of all events created by all nodes, in bytes. */
    private long createdBytes;

    /**
     * Registers a node of the network. Must be called for every node before the node reports any rounds.
     *
     * @param nodeId the ID of the node
     */
    void registerNode(@NonNull final NodeId nodeId) {
        nodeHistograms.computeIfAbsent(requireNonNull(nodeId), _ -> newHistogram());
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
     * <p>The C2C of every event of the round is recorded in the node's histogram. An event can never reach consensus
     * before it was created, because its creation time is derived from times the creator has already seen.
     *
     * @param nodeId the ID of the node that reached the round
     * @param round the round, as produced by the node's consensus engine
     * @throws IllegalStateException if the network has not been started yet
     * @throws IllegalArgumentException if the node has not been registered, or if an event of the round reached
     * consensus before it was created
     */
    void onConsensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {
        final LatencyHistogram histogram = nodeHistograms.get(nodeId);
        if (histogram == null) {
            throw new IllegalArgumentException("Node " + nodeId + " has not been registered");
        }

        final long reachedNanos = relativeNanos(round.getReachedConsTimestamp());
        for (final PlatformEvent event : round.getConsensusEvents()) {
            histogram.record(reachedNanos - relativeNanos(event.getTimeCreated()));
        }
    }

    /**
     * Creates a snapshot of everything recorded so far. The snapshot is independent of the recorder: recording more
     * rounds does not change it.
     *
     * @param now the current simulated time, which ends the measurement period
     * @return the snapshot
     * @throws IllegalStateException if the network has not been started yet
     */
    @NonNull
    ConsensusLatencyResult snapshot(@NonNull final Instant now) {
        final long elapsedNanos = relativeNanos(now);

        final LatencyHistogram network = newHistogram();
        final Map<NodeId, LatencyHistogram> perNode = new LinkedHashMap<>();
        for (final Map.Entry<NodeId, LatencyHistogram> entry : nodeHistograms.entrySet()) {
            network.add(entry.getValue());
            final LatencyHistogram copy = newHistogram();
            copy.add(entry.getValue());
            perNode.put(entry.getKey(), copy);
        }

        return new ConsensusLatencyResult(
                nodeHistograms.size(),
                network,
                perSecond(createdEvents, elapsedNanos),
                perSecond(createdBytes, elapsedNanos),
                perNode);
    }

    /**
     * Creates an empty histogram with the layout used for all C2C measurements.
     *
     * @return an empty histogram
     */
    @NonNull
    private static LatencyHistogram newHistogram() {
        return new LatencyHistogram(PRECISION_BITS, RANGE_BITS);
    }

    /**
     * Converts an instant to nanoseconds relative to the start of the network. Does not allocate.
     *
     * @param instant the instant to convert
     * @return the nanoseconds since the start, negative if the instant is before the start
     * @throws IllegalStateException if the network has not been started yet
     */
    private long relativeNanos(@NonNull final Instant instant) {
        final Instant origin = start;
        if (origin == null) {
            throw new IllegalStateException("The network has not been started yet");
        }
        return (instant.getEpochSecond() - origin.getEpochSecond()) * NANOS_PER_SECOND
                + (instant.getNano() - origin.getNano());
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
