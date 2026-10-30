// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;

/**
 * Keeps arrival times in strictly increasing order for each kind of copy on each connection. Copies of different kinds
 * do not constrain each other, so a later copy of one kind may arrive before an earlier copy of another.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class ArrivalTimeClamp {

    /**
     * The last arrival time handed out for each kind of copy on each connection.
     */
    private final Map<Key, Instant> lastArrivalTimes = new HashMap<>();

    /**
     * Identifies the copies that are kept in order relative to each other.
     *
     * @param connection the connection the copies travel on
     * @param kind       the kind of copy
     */
    private record Key(
            @NonNull ConnectionKey connection, @NonNull MessageRoute kind) {}

    /**
     * Gets the arrival time to use for a copy: the proposed time, or one nanosecond after the previous copy of the
     * same kind on the same connection if the proposal would be earlier than that.
     *
     * @param connection the connection the copy travels on
     * @param kind       the kind of copy
     * @param proposed   the arrival time the copy would have without clamping
     * @return the time the copy arrives
     */
    @NonNull
    Instant clamp(
            @NonNull final ConnectionKey connection,
            @NonNull final MessageRoute kind,
            @NonNull final Instant proposed) {
        final Key key = new Key(connection, kind);
        final Instant last = lastArrivalTimes.getOrDefault(key, Instant.MIN);
        final Instant arrival = proposed.isBefore(last) ? last.plusNanos(1L) : proposed;
        lastArrivalTimes.put(key, arrival);
        return arrival;
    }
}
