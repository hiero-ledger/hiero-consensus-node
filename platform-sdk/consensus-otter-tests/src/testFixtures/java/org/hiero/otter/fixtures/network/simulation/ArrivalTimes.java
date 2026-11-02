// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;

/**
 * Picks the time something put onto a connection of a simulated network arrives at the other end.
 *
 * <p>The time is the current time plus the connection's latency, offset by a jitter value drawn from a truncated
 * Gaussian distribution. A copy sent by sync also waits for the next sync to start. Jitter can pull an arrival time
 * earlier than one already scheduled on the same connection, so the result is clamped to be no earlier than the
 * previous one of the same kind on the same connection. That clamp keeps each kind of route in order.
 *
 * <p>Picking draws from the random number generator, so the order arrival times are picked in is part of what a seed
 * reproduces. A picked arrival time only counts for the clamp once it is committed, so that a copy that turns out not
 * to be sent does not hold up the ones after it. The average and earliest possible arrival times can be had without a
 * draw.
 *
 * <p>Arrival times are only picked while there is connection data, which then contains every connection.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster. Times are nanoseconds since the start of
 * the simulation, except for the arrival times handed out, which are instants.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class ArrivalTimes {

    /**
     * The instant the simulation starts at, which the times kept internally are measured from.
     */
    private final Instant start;

    /**
     * The random number generator the jitter is drawn from.
     */
    private final Random random;

    /**
     * The state of the connections the arrival times are picked for.
     */
    private final LinkStates links;

    /**
     * The production broadcast settings the delays of copies sent by sync are derived from.
     */
    private final BroadcastConfig broadcastConfig;

    /**
     * The production sync settings the delays of copies sent by sync are derived from.
     */
    private final SyncConfig syncConfig;

    /**
     * Keeps the arrival times of each kind of route on each connection in order, even when jitter is applied.
     */
    private final ArrivalTimeClamp clamp;

    /**
     * Constructor.
     *
     * @param start           the instant the simulation starts at
     * @param random          the random number generator to draw the jitter from
     * @param links           the state of the connections
     * @param nodeCount       the number of nodes in the network
     * @param broadcastConfig the production broadcast settings
     * @param syncConfig      the production sync settings
     */
    ArrivalTimes(
            @NonNull final Instant start,
            @NonNull final Random random,
            @NonNull final LinkStates links,
            final int nodeCount,
            @NonNull final BroadcastConfig broadcastConfig,
            @NonNull final SyncConfig syncConfig) {
        this.start = requireNonNull(start);
        this.random = requireNonNull(random);
        this.links = requireNonNull(links);
        this.broadcastConfig = requireNonNull(broadcastConfig);
        this.syncConfig = requireNonNull(syncConfig);
        this.clamp = new ArrivalTimeClamp(nodeCount);
    }

    /**
     * Picks the time something put onto a connection now arrives at the other end.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param kind     the route over which the message is sent
     * @param now      the current time
     * @return the time of arrival at the receiving end
     */
    @NonNull
    Instant decideNextArrivalTime(
            final int sender, final int receiver, @NonNull final MessageRoute kind, final long now) {
        final long arrival = propose(sender, receiver, kind, now);
        commit(sender, receiver, kind, arrival);
        return instantOf(arrival);
    }

    /**
     * Picks the time something put onto a connection now would arrive at the other end, like
     * {@link #decideNextArrivalTime(int, int, MessageRoute, long)}, but leaves it to the caller to decide whether it is put onto the
     * connection at all. The jitter is drawn either way; the arrival time only becomes the last one of its kind on the
     * connection once it is passed to {@link #commit(int, int, MessageRoute, long)}.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param kind     the route over which the message is sent
     * @param now      the current time
     * @return the time of arrival in nanoseconds
     */
    long propose(final int sender, final int receiver, @NonNull final MessageRoute kind, final long now) {
        // Simulate network latency and jitter using truncated Gaussian distribution
        final double sigma = links.jitterSigma(sender, receiver);
        final double jitter = Math.clamp(random.nextGaussian() * sigma, -3 * sigma, 3 * sigma);
        long arrivalTime = now + links.latency(sender, receiver) + (long) jitter;
        if (kind == MessageRoute.SYNC) {
            arrivalTime += syncDelay(sender, receiver, now);
        }

        // Ensure arrival time is always incremental within a kind of route
        return clamp.peek(sender, receiver, kind, arrivalTime);
    }

    /**
     * Records that something with the given arrival time is put onto a connection.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param kind     the route over which the message is sent
     * @param arrival  the arrival time, as returned by {@link #propose(int, int, MessageRoute, long)}
     */
    void commit(final int sender, final int receiver, @NonNull final MessageRoute kind, final long arrival) {
        clamp.commit(sender, receiver, kind, arrival);
    }

    /**
     * The earliest time something put onto a connection now can arrive, whatever jitter is drawn. No draw is made.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param kind     the route over which the message is sent
     * @param now      the current time
     * @return the earliest time of arrival in nanoseconds
     */
    long earliestPossible(final int sender, final int receiver, @NonNull final MessageRoute kind, final long now) {
        final double sigma = links.jitterSigma(sender, receiver);
        return expected(sender, receiver, kind, now) - (long) (3 * sigma);
    }

    /**
     * The time something put onto a connection now arrives at the other end on average, without jitter. No draw is made.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param kind     the route over which the message is sent
     * @param now      the current time
     * @return the average time of arrival in nanoseconds
     */
    long expected(final int sender, final int receiver, @NonNull final MessageRoute kind, final long now) {
        final long arrivalTime = now + links.latency(sender, receiver);
        return kind == MessageRoute.SYNC ? arrivalTime + syncDelay(sender, receiver, now) : arrivalTime;
    }

    /**
     * Converts a time in nanoseconds since the start of the simulation to an instant.
     *
     * @param nanos the time
     * @return the instant
     */
    @NonNull
    Instant instantOf(final long nanos) {
        return start.plusNanos(nanos);
    }

    /**
     * The time a copy sent by sync waits on top of the link latency: the hold-back applied by the sender, and the
     * average wait for the next sync to start. Syncs of a pair run back to back, each taking about two round trips plus
     * a pause, so the average wait is one round trip plus half the pause. Without broadcast there is no hold-back and
     * the pause is shorter.
     *
     * <p>The hold-back of 3 seconds that production applies to events the forwarder does not build on is not simulated.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param now      the current time
     * @return the delay in nanoseconds
     */
    private long syncDelay(final int sender, final int receiver, final long now) {
        final boolean broadcastMode = links.isBroadcastMode(sender, receiver, now);
        final long holdBack =
                broadcastMode ? syncConfig.ancestorFilterThreshold().toNanos() : 0L;
        final Duration pause =
                broadcastMode ? broadcastConfig.rpcSleepAfterSyncWhileBroadcasting() : syncConfig.rpcSleepAfterSync();
        return holdBack + links.roundTrip(sender, receiver) + pause.toNanos() / 2;
    }
}
