// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Utility methods for events.
 */
public final class EventUtils {
    /**
     * Hidden constructor.
     */
    private EventUtils() {}

    /**
     * Returns the timestamp of the last transaction in this event. If this event has no transactions, the event's
     * consensus timestamp is returned.
     *
     * @param event              the event to get the transaction time from
     * @param transactionOffsetNanos nanoseconds offset applied to user transactions from the event consensus timestamp
     * @return timestamp of the last transaction, or the event consensus timestamp if there are no transactions
     */
    public static @NonNull Instant getLastTransTime(
            @NonNull final PlatformEvent event, final long transactionOffsetNanos) {
        if (event.getConsensusTimestamp() == null) {
            throw new IllegalArgumentException("Event is not a consensus event");
        }
        if (event.getTransactionCount() == 0) {
            return event.getConsensusTimestamp();
        }
        return event.getTransactionTime(event.getTransactionCount() - 1, transactionOffsetNanos);
    }
}
