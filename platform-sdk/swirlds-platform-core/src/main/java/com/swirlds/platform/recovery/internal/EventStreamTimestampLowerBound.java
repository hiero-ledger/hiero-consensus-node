// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;
import org.hiero.consensus.model.event.CesEvent;

/**
 * A timestamp based lower bound on event streams.
 */
public class EventStreamTimestampLowerBound implements EventStreamLowerBound {

    /** the timestamp of the bound */
    private final Instant timestamp;

    /**
     * Create an event stream bound with the specified timestamp.
     *
     * @param timestamp the timestamp
     */
    public EventStreamTimestampLowerBound(@NonNull final Instant timestamp) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final CesEvent consensusEvent) {
        return consensusEvent.getPlatformEvent().getConsensusTimestamp().compareTo(timestamp);
    }
}
