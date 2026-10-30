// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * The event windows that have been transmitted onto the simulated network but not yet delivered, held per directed
 * connection in the same way {@link InFlightEvents} holds events.
 *
 * <p>An event window supersedes every window the same node sent before it, so a receiver only ever needs the newest one
 * that has arrived. Delivery therefore discards the windows a newer one has already superseded, and hands the receiver
 * a single window per sender no matter how many arrived since the last delivery.
 *
 * <p>That coalescing happens at delivery and never earlier. Collapsing windows while they are still in flight - keeping
 * one slot per connection and overwriting it - would let a sender that updates its window faster than the connection's
 * latency push the arrival time back on every overwrite, so the window would never become due and the receiver would
 * hear nothing at all.
 *
 * <p>This class is not safe to use from more than one thread.
 */
public class InFlightEventWindows extends InFlightConnections<EventWindowInTransit> {

    /**
     * Constructor.
     *
     * @param connected reports whether the connection from a sender to a receiver is currently up
     * @param start     the instant arrival times are measured from, which should be close to the start of the
     *                  simulation
     */
    public InFlightEventWindows(@NonNull final ConnectionCheck connected, @NonNull final Instant start) {
        super(connected, start);
    }

    /**
     * Delivers the newest event window each sender has landed at the receiving node, discarding the ones it
     * supersedes.
     *
     * <p>A connection whose windows have not arrived yet, or which is down, is passed over and keeps everything it is
     * holding. As with events, a receiver that refuses a window is taken to be refusing everything, so no further
     * senders are tried until the next call.
     *
     * @param now          the current time
     * @param receiverRank the rank of the receiving node
     * @param receiver     accepts the event windows on behalf of the receiving node
     */
    @Override
    void deliverArrived(@NonNull final Instant now, final int receiverRank, @NonNull final EventReceiver receiver) {
        final long nowNanos = nanosSinceStart(now);
        final InFlightQueue<EventWindowInTransit>[] incomingConnections = incomingConnections(receiverRank);

        for (int senderRank = 0; senderRank < incomingConnections.length; senderRank++) {
            final InFlightQueue<EventWindowInTransit> connection = incomingConnections[senderRank];
            if (connection == null) {
                // the receiver is the sender, so there is no connection
                continue;
            }
            if (!isUp(senderRank, receiverRank)) {
                // the connection is down, so its windows are not deliverable
                continue;
            }

            // Take the newest window that has arrived, which supersedes the ones taken before it. If the receiver
            // refuses it, it goes back, and the superseded windows stay discarded since the newest still supersedes
            // them.
            EventWindowInTransit newestArrived = null;
            while (connection.hasArrived(nowNanos)) {
                newestArrived = connection.poll();
            }

            if (newestArrived == null) {
                // nothing in flight on this connection has arrived yet
                continue;
            }

            if (!receiver.receiveEventWindow(newestArrived.sender(), newestArrived.eventWindow())) {
                connection.add(newestArrived);
                return;
            }
        }
    }
}
