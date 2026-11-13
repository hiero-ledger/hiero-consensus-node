// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.latency;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;

/**
 * Collects the creation-to-consensus latency (C2C), the event throughput, and the estimated duplicate traffic of a
 * Falcon network, in simulated time.
 *
 * <p>Only full nodes run a consensus engine, so only they report consensus rounds. Every full node reports each of its
 * rounds directly from the output of its own consensus engine. Rounds cannot be taken from the subscribers of the
 * results collectors, because those receive the instance interned in the
 * {@link org.hiero.otter.fixtures.internal.result.ConsensusRoundPool}, which keeps only one instance per round, carrying
 * the {@link ConsensusRound#getReachedConsTimestamp() reached timestamp} of the first node that reported it.
 *
 * <p>Every node, full node and mini-node alike, reports each event it creates, so that the throughput covers the whole
 * network.
 *
 * <p>Recording a C2C value takes a few nanoseconds and never allocates, which is the same order of work as the consensus
 * engine does for the event, so the recorder is cheap enough to be always on. Each full node owns a histogram of
 * {@link #PRECISION_BITS} precision bits and {@link #RANGE_BITS} range bits.
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

    /** The C2C observed by each full node, in node registration order. */
    private final Map<NodeId, LatencyHistogram> nodeHistograms = new LinkedHashMap<>();

    /** The IDs of all nodes of the network, full nodes and mini-nodes alike. */
    private final Set<NodeId> allNodes = new HashSet<>();

    /** The simulated time at which the network was started, or {@code null} if it has not been started yet. */
    @Nullable
    private Instant start;

    /** The number of events created by all nodes. */
    private long createdEvents;

    /** The total serialized size of all events created by all nodes, in bytes. */
    private long createdBytes;

    /**
     * The number of duplicate copies of events that production gossip would send by sync to the full nodes, as estimated
     * by the network.
     */
    private long syncDuplicateCopies;

    /**
     * The number of duplicate copies of events that production gossip would broadcast to the full nodes, as estimated by
     * the network.
     */
    private long broadcastDuplicateCopies;

    /** The total serialized size of all duplicate copies to the full nodes, sync and broadcast, in bytes. */
    private long duplicateBytes;

    /**
     * Registers a node of the network. Must be called for every node, and for a full node before it reports any
     * rounds.
     *
     * @param nodeId the ID of the node
     * @param fullNode {@code true} if the node runs a consensus engine and reports its rounds, {@code false} for a
     * mini-node
     */
    void registerNode(@NonNull final NodeId nodeId, final boolean fullNode) {
        allNodes.add(requireNonNull(nodeId));
        if (fullNode) {
            nodeHistograms.computeIfAbsent(nodeId, _ -> newHistogram());
        }
    }

    /**
     * Gets the full nodes registered so far, which are the nodes the C2C is observed by and the duplicates are
     * estimated for.
     *
     * @return the full nodes, in registration order
     */
    @NonNull
    List<NodeId> fullNodes() {
        return List.copyOf(nodeHistograms.keySet());
    }

    /**
     * Marks the moment the network was started. Only the first call has an effect.
     *
     * @param now the current simulated time
     */
    void markStarted(@NonNull final Instant now) {
        if (start == null) {
            start = requireNonNull(now);
        }
    }

    /**
     * Discards everything recorded so far and starts a new measurement period now. Events created before this moment
     * are left out of the C2C even if they reach consensus later, so that a measurement can skip the start-up of the
     * network, during which the mini-nodes already create events but the full nodes do not run consensus yet.
     *
     * @param now the current simulated time, which starts the new measurement period
     * @throws IllegalStateException if the network has not been started yet
     */
    void restart(@NonNull final Instant now) {
        if (start == null) {
            throw new IllegalStateException("The network has not been started yet");
        }
        start = requireNonNull(now);
        nodeHistograms.values().forEach(LatencyHistogram::reset);
        createdEvents = 0;
        createdBytes = 0;
        syncDuplicateCopies = 0;
        broadcastDuplicateCopies = 0;
        duplicateBytes = 0;
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
     * Records duplicate copies of an event that production gossip would send to a full node, as estimated by the
     * simulated network. The duplicates are reported once the first copy of the event has arrived at the node, so those
     * of an event created shortly before a {@link #restart(Instant) restart} can count towards the new measurement
     * period, and those of an event created shortly before a snapshot do not count yet.
     *
     * @param receiver        the full node the duplicate copies are sent to
     * @param event           the event
     * @param syncCopies      the number of duplicate copies sent by sync
     * @param broadcastCopies the number of duplicate copies broadcast by the creator of the event
     * @throws IllegalArgumentException if the receiver is not a registered full node
     */
    void onEstimatedDuplicates(
            @NonNull final NodeId receiver,
            @NonNull final PlatformEvent event,
            final int syncCopies,
            final int broadcastCopies) {
        if (!nodeHistograms.containsKey(receiver)) {
            throw new IllegalArgumentException("Node " + receiver + " is not a registered full node");
        }
        syncDuplicateCopies += syncCopies;
        broadcastDuplicateCopies += broadcastCopies;
        duplicateBytes +=
                (long) (syncCopies + broadcastCopies) * GossipEvent.PROTOBUF.measureRecord(event.getGossipEvent());
    }

    /**
     * Records a consensus round reached by a full node. Must be called with the node's own instance of the round, as
     * produced by its consensus engine, and not with the instance interned in the consensus round pool.
     *
     * <p>Only the events created in the current measurement period are recorded, see {@link #restart(Instant)}.
     *
     * @param nodeId the ID of the node that reached the round
     * @param round the round, as produced by the node's consensus engine
     * @throws IllegalStateException if the network has not been started yet
     * @throws IllegalArgumentException if the node has not been registered, or if an event of the round reached
     * consensus before it was created
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    void onConsensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {
        final LatencyHistogram histogram = nodeHistograms.get(nodeId);
        if (histogram == null) {
            throw new IllegalArgumentException("Node " + nodeId + " has not been registered");
        }

        final long reachedNanos = relativeNanos(round.getReachedConsTimestamp());
        final List<PlatformEvent> events = round.getConsensusEvents();
        for (int i = 0, n = events.size(); i < n; i++) {
            final long createdNanos = relativeNanos(events.get(i).getTimeCreated());
            if (createdNanos < 0) {
                // created before the measurement period
                continue;
            }
            final long c2c = reachedNanos - createdNanos;
            if (c2c < 0) {
                throw new IllegalArgumentException("Event reached consensus " + -c2c + "ns before it was created");
            }
            histogram.record(c2c);
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
                allNodes.size(),
                network,
                perSecond(createdEvents, elapsedNanos),
                perSecond(createdBytes, elapsedNanos),
                perSecondPerNode(syncDuplicateCopies, elapsedNanos, nodeHistograms.size()),
                perSecondPerNode(broadcastDuplicateCopies, elapsedNanos, nodeHistograms.size()),
                perSecondPerNode(duplicateBytes, elapsedNanos, nodeHistograms.size()),
                perNode);
    }

    /**
     * Creates an empty histogram with the layout used for all C2C measurements.
     *
     * @return an empty histogram
     */
    @NonNull
    static LatencyHistogram newHistogram() {
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
        return elapsedNanos <= 0 ? 0 : (long) (amount * (double) NANOS_PER_SECOND / elapsedNanos);
    }

    /**
     * Converts an amount counted over a number of nodes to a rate per second of each node, on average.
     *
     * @param amount       the amount, over all the nodes
     * @param elapsedNanos the time the amount was counted over, in nanoseconds
     * @param nodes        the number of nodes the amount was counted over
     * @return the amount per second per node, or 0 if no time has elapsed or there are no nodes
     */
    private static long perSecondPerNode(final long amount, final long elapsedNanos, final int nodes) {
        return elapsedNanos <= 0 || nodes == 0 ? 0 : (long) (amount * (double) NANOS_PER_SECOND / elapsedNanos / nodes);
    }
}
