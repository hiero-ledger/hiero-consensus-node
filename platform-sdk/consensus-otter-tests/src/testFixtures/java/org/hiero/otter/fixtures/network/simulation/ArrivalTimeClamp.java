// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Keeps arrival times in strictly increasing order for each kind of copy on each connection. Copies of different kinds
 * do not constrain each other, so a later copy of one kind may arrive before an earlier copy of another.
 *
 * <p>A connection is identified by the ranks of its sender and receiver, which are their indices in the roster.
 * Arrival times are nanoseconds since the start of the simulation.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class ArrivalTimeClamp {

    /**
     * The last arrival time handed out for each kind of copy on each connection, indexed by the ordinal of the kind,
     * then by the rank of the sender and then by the rank of the receiver. Zero, the start of the simulation, if there
     * was none. Arrival times are never earlier than that, unless jitter outweighs the latency of a copy sent right at
     * the start, and such a copy is moved to just after the start.
     */
    private final long[][][] lastArrivalTimes;

    /**
     * Constructor.
     *
     * @param nodeCount the number of nodes in the network
     */
    ArrivalTimeClamp(final int nodeCount) {
        lastArrivalTimes = new long[MessageRoute.values().length][nodeCount][nodeCount];
    }

    /**
     * Gets the arrival time to use for a copy: the proposed time, or one nanosecond after the previous copy of the
     * same kind on the same connection if the proposal would be earlier than that.
     *
     * @param senderRank   the rank of the node at the sending end of the connection
     * @param receiverRank the rank of the node at the receiving end of the connection
     * @param kind         the kind of copy
     * @param proposed     the arrival time the copy would have without clamping
     * @return the time the copy arrives
     */
    long clamp(final int senderRank, final int receiverRank, @NonNull final MessageRoute kind, final long proposed) {
        final long arrival = peek(senderRank, receiverRank, kind, proposed);
        commit(senderRank, receiverRank, kind, arrival);
        return arrival;
    }

    /**
     * Gets the arrival time {@link #clamp(int, int, MessageRoute, long)} would return, without recording it as the
     * last one handed out. For a copy that may turn out not to be sent.
     *
     * @param senderRank   the rank of the node at the sending end of the connection
     * @param receiverRank the rank of the node at the receiving end of the connection
     * @param kind         the kind of copy
     * @param proposed     the arrival time the copy would have without clamping
     * @return the time the copy would arrive
     */
    long peek(final int senderRank, final int receiverRank, @NonNull final MessageRoute kind, final long proposed) {
        final long last = lastArrivalTimes[kind.ordinal()][senderRank][receiverRank];
        return proposed < last ? last + 1 : proposed;
    }

    /**
     * Records the arrival time of a copy that is sent as the last one handed out.
     *
     * @param senderRank   the rank of the node at the sending end of the connection
     * @param receiverRank the rank of the node at the receiving end of the connection
     * @param kind         the kind of copy
     * @param arrival      the time the copy arrives, as returned by {@link #peek(int, int, MessageRoute, long)}
     */
    void commit(final int senderRank, final int receiverRank, @NonNull final MessageRoute kind, final long arrival) {
        lastArrivalTimes[kind.ordinal()][senderRank][receiverRank] = arrival;
    }
}
