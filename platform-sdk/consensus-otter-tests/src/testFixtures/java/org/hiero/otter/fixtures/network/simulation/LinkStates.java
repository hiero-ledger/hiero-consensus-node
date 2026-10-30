// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;

/**
 * The state of every connection and every link of a simulated network: what the connection data says about each
 * connection, whether each link is up, its round trip, the reconnect delay it may be waiting out, and whether it is in
 * broadcast mode.
 *
 * <p>A connection goes one way, from a sender to a receiver. A link is a pair of nodes and covers the connections in
 * both directions; it is up only if both of them are connected. Nodes are identified by their rank, see
 * {@link NodeRanks}. Everything is kept in arrays indexed by the ranks of the two nodes involved, because it is read
 * for every copy of every event. The state of a link is kept the same both ways round, so that it can be read without
 * first ordering the two ranks.
 *
 * <p>Times are nanoseconds since the start of the simulation.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class LinkStates {

    /**
     * How many round trips of a link pass between it coming up and anything being delivered over it. This stands in
     * for the TCP and TLS handshakes, protocol negotiation and first sync.
     */
    private static final double RECONNECT_ROUND_TRIPS = 5.0;

    /**
     * The reconnect delay is randomly varied by up to this fraction in either direction, so that links which come up
     * together do not all become usable at the same moment.
     */
    private static final double RECONNECT_JITTER = 0.2;

    /**
     * Stands for a time that lies before any other, such as the end of a reconnect delay that a link is not waiting out.
     */
    private static final long NO_TIME = Long.MIN_VALUE;

    /**
     * Stands for a time that never comes, such as the time a link that is too slow returns to broadcast mode.
     */
    private static final long NEVER = Long.MAX_VALUE;

    /**
     * Is told about each link that has come up.
     */
    @FunctionalInterface
    interface LinkUpListener {

        /**
         * Called for a link that has come up.
         *
         * @param first  the rank of the node of the link that comes first in node id order
         * @param second the rank of the other node of the link
         */
        void linkCameUp(int first, int second);
    }

    /**
     * The random number generator the reconnect delays are varied with.
     */
    private final Random random;

    /**
     * The production broadcast settings that decide when a link is in broadcast mode.
     */
    private final BroadcastConfig broadcastConfig;

    /**
     * The number of nodes in the network.
     */
    private final int nodeCount;

    /**
     * Whether the most recent connection data contained any connection. The first connection data that does is taken
     * to describe links that are already established.
     */
    private boolean hasConnections = false;

    /**
     * Whether the connection data contains the connection from one node to another, indexed by the rank of the sender
     * and then by the rank of the receiver. Something sent over a connection that is not in the data is dropped.
     */
    private final boolean[][] connectionKnown;

    /**
     * Whether the connection from one node to another is connected, indexed like {@link #connectionKnown}.
     */
    private final boolean[][] connectionUp;

    /**
     * The latency of the connection from one node to another, indexed like {@link #connectionKnown}.
     */
    private final long[][] latencies;

    /**
     * The standard deviation of the jitter of the connection from one node to another, indexed like
     * {@link #connectionKnown}.
     */
    private final double[][] jitterSigmas;

    /**
     * Whether the link between two nodes is up, indexed by the ranks of the two nodes in either order.
     */
    private final boolean[][] linkUp;

    /**
     * Whether each link was up before the connection data was last replaced, indexed like {@link #linkUp}. Only used
     * during {@link #update(Map, NodeRanks, long, LinkUpListener)}, and kept between calls only so that it does not have
     * to be allocated on every call.
     */
    private final boolean[][] wasLinkUp;

    /**
     * The sum of the latencies of both directions of a link, indexed like {@link #linkUp}. A missing direction counts as
     * the other one. Only meaningful if at least one direction is in the connection data.
     */
    private final long[][] roundTrips;

    /**
     * The time before which nothing is delivered over a link in either direction, indexed like {@link #linkUp}.
     * {@link #NO_TIME} if the link has no reconnect delay pending.
     */
    private final long[][] reconnectReadyAt;

    /**
     * The time from which a link is in broadcast mode, indexed like {@link #linkUp}. {@link #NEVER} while the round trip
     * of the link is above the threshold, and {@link #NO_TIME} if the link has always been in broadcast mode.
     */
    private final long[][] broadcastEnabledAt;

    /**
     * Constructor. Until the first call to {@link #update(Map, NodeRanks, long, LinkUpListener)} no connection is
     * known.
     *
     * @param nodeCount       the number of nodes in the network
     * @param random          the random number generator to vary the reconnect delays with
     * @param broadcastConfig the production broadcast settings
     */
    LinkStates(final int nodeCount, @NonNull final Random random, @NonNull final BroadcastConfig broadcastConfig) {
        this.nodeCount = nodeCount;
        this.random = requireNonNull(random);
        this.broadcastConfig = requireNonNull(broadcastConfig);
        connectionKnown = new boolean[nodeCount][nodeCount];
        connectionUp = new boolean[nodeCount][nodeCount];
        latencies = new long[nodeCount][nodeCount];
        jitterSigmas = new double[nodeCount][nodeCount];
        linkUp = new boolean[nodeCount][nodeCount];
        wasLinkUp = new boolean[nodeCount][nodeCount];
        roundTrips = new long[nodeCount][nodeCount];
        reconnectReadyAt = filled(nodeCount, NO_TIME);
        broadcastEnabledAt = filled(nodeCount, NO_TIME);
    }

    /**
     * Replaces the connection data, and works out what that means for every link.
     *
     * <p>The new state is compared with the old one link by link. A link that has gone down loses any reconnect delay it
     * was waiting out, so that nothing gets through it before a full delay has passed after it next comes up. The
     * listener is told about every link that has come up, in rank order, so that it can start the reconnect delay of
     * the link with {@link #startReconnectDelay(int, int, long)} and do whatever else a new link calls for.
     *
     * <p>The first connection data that contains any connection is taken to describe links that are already
     * established, so the listener is not told about any link for it.
     *
     * @param connections the new connection data
     * @param ranks       the ranks of the nodes of the network
     * @param now         the current time
     * @param listener    is told about every link that has come up
     * @throws IllegalArgumentException if the data contains a connection of a node that is not part of the network
     */
    void update(
            @NonNull final Map<ConnectionKey, ConnectionState> connections,
            @NonNull final NodeRanks ranks,
            final long now,
            @NonNull final LinkUpListener listener) {
        final boolean initial = !hasConnections;
        for (int node = 0; node < nodeCount; node++) {
            System.arraycopy(linkUp[node], 0, wasLinkUp[node], 0, nodeCount);
        }

        for (int sender = 0; sender < nodeCount; sender++) {
            Arrays.fill(connectionKnown[sender], false);
            Arrays.fill(connectionUp[sender], false);
            Arrays.fill(latencies[sender], 0L);
            Arrays.fill(jitterSigmas[sender], 0.0);
        }
        for (final Map.Entry<ConnectionKey, ConnectionState> entry : connections.entrySet()) {
            final int sender = ranks.rankOf(entry.getKey().sender());
            final int receiver = ranks.rankOf(entry.getKey().receiver());
            final ConnectionState state = entry.getValue();
            connectionKnown[sender][receiver] = true;
            connectionUp[sender][receiver] = state.connected();
            latencies[sender][receiver] = state.latency().toNanos();
            jitterSigmas[sender][receiver] = state.latency().toNanos() * state.jitter().value / 100.0;
        }
        for (int first = 0; first < nodeCount; first++) {
            for (int second = first + 1; second < nodeCount; second++) {
                setForLink(linkUp, first, second, connectionUp[first][second] && connectionUp[second][first]);
                setForLink(roundTrips, first, second, computeRoundTrip(first, second));
            }
        }
        hasConnections = !connections.isEmpty();

        updateBroadcastModes(now);
        if (initial) {
            for (final long[] row : reconnectReadyAt) {
                Arrays.fill(row, NO_TIME);
            }
            return;
        }

        for (int first = 0; first < nodeCount; first++) {
            for (int second = first + 1; second < nodeCount; second++) {
                if (!linkUp[first][second]) {
                    setForLink(reconnectReadyAt, first, second, NO_TIME);
                } else if (!wasLinkUp[first][second]) {
                    listener.linkCameUp(first, second);
                }
            }
        }
    }

    /**
     * Tells the listener about every link of a node that is up, as if each of them had just come up, which is what
     * happens when the node restarts.
     *
     * @param node     the rank of the node
     * @param listener is told about every link of the node that is up, in rank order of the peer
     */
    void restartLinksOf(final int node, @NonNull final LinkUpListener listener) {
        for (int peer = 0; peer < nodeCount; peer++) {
            if (peer != node && linkUp[node][peer]) {
                listener.linkCameUp(Math.min(node, peer), Math.max(node, peer));
            }
        }
    }

    /**
     * Starts the reconnect delay of a link that has just come up. The delay is a fixed number of round trips of the
     * link, varied by a little jitter drawn from the random number generator.
     *
     * @param first  the rank of one node of the link
     * @param second the rank of the other node of the link
     * @param now    the current time
     */
    void startReconnectDelay(final int first, final int second, final long now) {
        final long roundTrip = latencies[first][second] + latencies[second][first];
        final double jitter = RECONNECT_JITTER * (2 * random.nextDouble() - 1);
        final long delay = (long) (roundTrip * RECONNECT_ROUND_TRIPS * (1 + jitter));
        setForLink(reconnectReadyAt, first, second, now + delay);
    }

    /**
     * @return {@code true} if the most recent connection data contained any connection
     */
    boolean hasConnections() {
        return hasConnections;
    }

    /**
     * Reports whether the connection data contains a connection. Nothing can be sent over a connection that is not in
     * the data.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @return {@code true} if the connection is in the data
     */
    boolean isKnown(final int sender, final int receiver) {
        return connectionKnown[sender][receiver];
    }

    /**
     * @return the latency of the connection from the sender to the receiver
     */
    long latency(final int sender, final int receiver) {
        return latencies[sender][receiver];
    }

    /**
     * @return the standard deviation of the jitter of the connection from the sender to the receiver
     */
    double jitterSigma(final int sender, final int receiver) {
        return jitterSigmas[sender][receiver];
    }

    /**
     * @return the sum of the latencies of both directions of the link between two nodes
     */
    long roundTrip(final int a, final int b) {
        return roundTrips[a][b];
    }

    /**
     * Reports whether things can currently be delivered from a sender to a receiver.
     *
     * <p>This requires the connection to be up in both directions, not only from the sender to the receiver. In
     * production the two nodes share one TCP connection, and the side that stops receiving closes it. It also requires
     * the reconnect delay, if the link came up recently, to have passed.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param now      the current time
     * @return {@code true} if things can travel from the sender to the receiver
     */
    boolean isConnected(final int sender, final int receiver, final long now) {
        return linkUp[sender][receiver] && now >= reconnectReadyAt[sender][receiver];
    }

    /**
     * Reports whether the link between two nodes is in broadcast mode.
     *
     * @param a   the rank of one node of the link
     * @param b   the rank of the other node of the link
     * @param now the current time
     * @return {@code true} if the link is in broadcast mode
     */
    boolean isBroadcastMode(final int a, final int b, final long now) {
        return now >= broadcastEnabledAt[a][b];
    }

    /**
     * Updates the broadcast mode of every link in the connection data from its current round trip. A link leaves
     * broadcast mode when its round trip exceeds the threshold, and returns to it a fixed time after the round trip
     * falls below the threshold again.
     *
     * @param now the current time
     */
    private void updateBroadcastModes(final long now) {
        for (int first = 0; first < nodeCount; first++) {
            for (int second = first + 1; second < nodeCount; second++) {
                if (!connectionKnown[first][second] && !connectionKnown[second][first]) {
                    continue;
                }
                if (roundTrips[first][second]
                        > broadcastConfig.disablePingThreshold().toNanos()) {
                    setForLink(broadcastEnabledAt, first, second, NEVER);
                } else if (broadcastEnabledAt[first][second] == NEVER) {
                    setForLink(
                            broadcastEnabledAt,
                            first,
                            second,
                            now + broadcastConfig.pauseOnLag().toNanos());
                }
            }
        }
    }

    /**
     * Works out the sum of the latencies of both directions of a link from the connection data. A missing direction
     * counts as the other one.
     *
     * @param a the rank of one node of the link
     * @param b the rank of the other node of the link
     * @return the round trip, or {@code 0} if neither direction is in the connection data
     */
    private long computeRoundTrip(final int a, final int b) {
        final long forward = connectionKnown[a][b] ? latencies[a][b] : latencies[b][a];
        final long backward = connectionKnown[b][a] ? latencies[b][a] : forward;
        return forward + backward;
    }

    private static void setForLink(@NonNull final boolean[][] array, final int a, final int b, final boolean value) {
        array[a][b] = value;
        array[b][a] = value;
    }

    private static void setForLink(@NonNull final long[][] array, final int a, final int b, final long value) {
        array[a][b] = value;
        array[b][a] = value;
    }

    @NonNull
    private static long[][] filled(final int size, final long value) {
        final long[][] array = new long[size][size];
        for (final long[] row : array) {
            Arrays.fill(row, value);
        }
        return array;
    }
}
