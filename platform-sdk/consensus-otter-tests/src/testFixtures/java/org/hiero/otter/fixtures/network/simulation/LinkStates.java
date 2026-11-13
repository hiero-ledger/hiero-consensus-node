// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;

/**
 * The state of every connection and every link of a simulated network: what the connection data says about each
 * connection, whether each link is up, its round trip, the reconnect delay it may be waiting out, and whether it is in
 * broadcast mode.
 *
 * <p>A connection goes one way, from a sender to a receiver. A link is a pair of nodes and covers the connections in
 * both directions; it is up only if both of them are connected. Nodes are identified by their rank, which is their
 * index in the roster. Everything is kept in arrays indexed by the ranks of the two nodes involved, because it is read
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
     * The start of the simulation. No time lies before it, so it stands for a time that has always passed, such as the
     * end of a reconnect delay that a link is not waiting out. It is zero, which is what new arrays are filled with.
     */
    private static final long START = 0L;

    /**
     * Stands for a time that never comes, such as the time a link that is too slow returns to broadcast mode.
     */
    private static final long NEVER = Long.MAX_VALUE;

    /**
     * Is told about each link that has come up or gone down.
     */
    interface LinkListener {

        /**
         * Called for a link that has come up.
         *
         * @param first  the rank of the node of the link that comes first in roster order
         * @param second the rank of the other node of the link
         */
        void linkCameUp(int first, int second);

        /**
         * Called for a link that has gone down.
         *
         * @param first  the rank of the node of the link that comes first in roster order
         * @param second the rank of the other node of the link
         */
        void linkWentDown(int first, int second);

        /**
         * Called for a link that is up when one of its nodes restarts. The link has been up all along, but the restarted
         * node has to establish the connection again.
         *
         * @param first  the rank of the node of the link that comes first in roster order
         * @param second the rank of the other node of the link
         */
        void linkRestarted(int first, int second);
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
     * Whether any connection data has been given yet. The first connection data is taken to describe links that are
     * already established.
     */
    private boolean connectionsInitialized = false;

    /**
     * The latency of the connection from one node to another, indexed by the rank of the sender and then by the rank of
     * the receiver.
     */
    private final long[][] latencies;

    /**
     * The standard deviation of the jitter of the connection from one node to another, indexed like
     * {@link #latencies}.
     */
    private final double[][] jitterSigmas;

    /**
     * Whether the link between two nodes is up, indexed by the ranks of the two nodes in either order.
     *
     * <p>A link is up only if the connection data has the connections in both directions connected. In production the
     * two nodes share one TCP connection, and the side that stops receiving closes it, so a cut in one direction stops
     * both. This is what decides whether something can be delivered in either direction, and its changes are what count
     * as a link coming up or going down.
     */
    private final boolean[][] linkUp;

    /**
     * Whether each link was up before the connection data was last replaced, indexed like {@link #linkUp}. Only used
     * during {@link #update(Map, ToIntFunction, long, LinkListener)}, and kept between calls only so that it does not
     * have to be allocated on every call.
     */
    private final boolean[][] wasLinkUp;

    /**
     * The sum of the latencies of both directions of a link, indexed like {@link #linkUp}.
     */
    private final long[][] roundTrips;

    /**
     * The time before which nothing is delivered over a link in either direction, indexed like {@link #linkUp}.
     * {@link #START} if the link has no reconnect delay pending.
     */
    private final long[][] reconnectReadyAt;

    /**
     * The time from which a link is in broadcast mode, indexed like {@link #linkUp}. {@link #NEVER} while the round trip
     * of the link is above the threshold, and {@link #START} if the link has always been in broadcast mode.
     */
    private final long[][] broadcastEnabledAt;

    /**
     * Constructor. Until the first call to {@link #update(Map, ToIntFunction, long, LinkListener)} no connection is
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
        latencies = new long[nodeCount][nodeCount];
        jitterSigmas = new double[nodeCount][nodeCount];
        linkUp = new boolean[nodeCount][nodeCount];
        wasLinkUp = new boolean[nodeCount][nodeCount];
        roundTrips = new long[nodeCount][nodeCount];
        reconnectReadyAt = new long[nodeCount][nodeCount];
        broadcastEnabledAt = new long[nodeCount][nodeCount];
    }

    /**
     * Replaces the connection data, and works out what that means for every link.
     *
     * <p>The new state is compared with the old one link by link. A link that has gone down loses any reconnect delay it
     * was waiting out, so that nothing gets through it before a full delay has passed after it next comes up. The
     * listener is told about every link that has come up, in rank order, so that it can start the reconnect delay of
     * the link with {@link #startReconnectDelay(int, int, long)} and do whatever else a new link calls for. It is told
     * about every link that has gone down too, with the new state of all links already in place.
     *
     * <p>The first connection data is taken to describe links that are already established, so the listener is not told
     * about any link for it.
     *
     * <p>The connection data is complete: it contains the connection from every node to every other node, so it is
     * empty only for a network of a single node. A connection that is cut is in the data as one that is not connected.
     *
     * @param connections the new connection data
     * @param rankOf      gets the rank of a node, and throws if the node is not part of the network
     * @param now         the current time
     * @param listener    is told about every link that has come up or gone down
     * @throws IllegalArgumentException if the data is not complete, or contains a connection of a node that is not part
     *                                  of the network
     */
    void update(
            @NonNull final Map<ConnectionKey, ConnectionState> connections,
            @NonNull final ToIntFunction<NodeId> rankOf,
            final long now,
            @NonNull final LinkListener listener) {
        if (connections.size() != nodeCount * (nodeCount - 1)) {
            throw new IllegalArgumentException("The connection data has " + connections.size()
                    + " connections, but must have one between every two of the " + nodeCount + " nodes");
        }
        final boolean initial = !connectionsInitialized;
        connectionsInitialized = true;
        for (int node = 0; node < nodeCount; node++) {
            System.arraycopy(linkUp[node], 0, wasLinkUp[node], 0, nodeCount);
        }

        for (int sender = 0; sender < nodeCount; sender++) {
            Arrays.fill(linkUp[sender], false);
            Arrays.fill(latencies[sender], 0L);
            Arrays.fill(jitterSigmas[sender], 0.0);
        }
        for (final Map.Entry<ConnectionKey, ConnectionState> entry : connections.entrySet()) {
            final int sender = rankOf.applyAsInt(entry.getKey().sender());
            final int receiver = rankOf.applyAsInt(entry.getKey().receiver());
            if (sender == receiver) {
                throw new IllegalArgumentException("The connection data has a connection of a node to itself");
            }
            final ConnectionState state = entry.getValue();
            // only one direction for now; both directions of a link are made to agree below
            linkUp[sender][receiver] = state.connected();
            latencies[sender][receiver] = state.latency().toNanos();
            jitterSigmas[sender][receiver] = state.latency().toNanos() * state.jitter().value / 100.0;
        }
        for (int first = 0; first < nodeCount; first++) {
            for (int second = first + 1; second < nodeCount; second++) {
                setForLink(linkUp, first, second, linkUp[first][second] && linkUp[second][first]);
                setForLink(roundTrips, first, second, latencies[first][second] + latencies[second][first]);
            }
        }
        updateBroadcastModes(now);
        if (initial) {
            // No link has a reconnect delay yet, and none is taken to have come up.
            return;
        }

        for (int first = 0; first < nodeCount; first++) {
            for (int second = first + 1; second < nodeCount; second++) {
                if (!linkUp[first][second]) {
                    setForLink(reconnectReadyAt, first, second, START);
                    if (wasLinkUp[first][second]) {
                        listener.linkWentDown(first, second);
                    }
                } else if (!wasLinkUp[first][second]) {
                    listener.linkCameUp(first, second);
                }
            }
        }
    }

    /**
     * Tells the listener about every link of a node that is up, because the node has restarted and has to establish
     * each of those connections again.
     *
     * @param node     the rank of the node
     * @param listener is told about every link of the node that is up, in rank order of the peer
     */
    void restartLinksOf(final int node, @NonNull final LinkListener listener) {
        for (int peer = 0; peer < nodeCount; peer++) {
            if (peer != node && linkUp[node][peer]) {
                listener.linkRestarted(Math.min(node, peer), Math.max(node, peer));
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
     * @return {@code true} if any connection data has been given yet
     */
    boolean connectionsInitialized() {
        return connectionsInitialized;
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
     * Reports whether the link between two nodes is up, which it is if both of its connections are connected. A link
     * that is up may still be waiting out a reconnect delay, see {@link #usableFrom(int, int)}.
     *
     * @param a the rank of one node of the link
     * @param b the rank of the other node of the link
     * @return {@code true} if the link is up
     */
    boolean isLinkUp(final int a, final int b) {
        return linkUp[a][b];
    }

    /**
     * The time from which things can be delivered over a link that is up, which is later than the time the link came up
     * if it has a reconnect delay to wait out.
     *
     * @param a the rank of one node of the link
     * @param b the rank of the other node of the link
     * @return the time, or the start of the simulation if the link has no reconnect delay pending
     */
    long usableFrom(final int a, final int b) {
        return reconnectReadyAt[a][b];
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

    private static void setForLink(@NonNull final boolean[][] array, final int a, final int b, final boolean value) {
        array[a][b] = value;
        array[b][a] = value;
    }

    private static void setForLink(@NonNull final long[][] array, final int a, final int b, final long value) {
        array[a][b] = value;
        array[b][a] = value;
    }
}
