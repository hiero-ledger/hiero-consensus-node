// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/**
 * Keeps arrival times in strictly increasing order for each kind of copy on each connection. Copies of different kinds
 * do not constrain each other, so a later copy of one kind may arrive before an earlier copy of another.
 *
 * <p>A connection is identified by the ranks of its sender and receiver, which are their positions in node id order.
 * Arrival times are nanoseconds since the start of the simulation.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class ArrivalTimeClamp {

    /**
     * The last arrival time handed out for each kind of copy on each connection, indexed by the ordinal of the kind,
     * then by the rank of the sender and then by the rank of the receiver. {@link Long#MIN_VALUE} if there was none.
     */
    private final long[][][] lastArrivalTimes;

    /**
     * Constructor.
     *
     * @param nodeCount the number of nodes in the network
     */
    ArrivalTimeClamp(final int nodeCount) {
        lastArrivalTimes = new long[MessageRoute.values().length][nodeCount][nodeCount];
        for (final long[][] byKind : lastArrivalTimes) {
            for (final long[] bySender : byKind) {
                Arrays.fill(bySender, Long.MIN_VALUE);
            }
        }
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
        final long[] lastByReceiver = lastArrivalTimes[kind.ordinal()][senderRank];
        final long last = lastByReceiver[receiverRank];
        final long arrival = proposed < last ? last + 1 : proposed;
        lastByReceiver[receiverRank] = arrival;
        return arrival;
    }
}
