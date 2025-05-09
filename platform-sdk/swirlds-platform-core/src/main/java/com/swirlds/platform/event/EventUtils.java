// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Utility methods for events.
 */
public final class EventUtils {
    /**
     * Hidden constructor
     */
    private EventUtils() {}

    /**
     * Returns the timestamp of the last transaction in this event. If this event has no transaction, then the timestamp
     * of the event will be returned
     *
     * @param event the event to get the transaction time from
     * @return timestamp of the last transaction
     */
    public static @NonNull Instant getLastTransTime(@NonNull final PlatformEvent event) {
        if (event.getConsensusTimestamp() == null) {
            throw new IllegalArgumentException("Event is not a consensus event");
        }
        // this is a special case. if an event has 0 or 1 transactions, the timestamp of the last transaction can be
        // considered to be the same, equivalent to the timestamp of the event
        if (event.getTransactionCount() <= 1) {
            return event.getConsensusTimestamp();
        }
        return event.getTransactionTime(event.getTransactionCount() - 1);
    }
}
