// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.CesEvent;

/**
 * A round based lower bound on an event stream.
 */
public class EventStreamRoundLowerBound implements EventStreamLowerBound {

    /** the round of the lower bound */
    private final long round;

    /**
     * Create an event stream round lower bound with the specified round.
     *
     * @param round     the round
     * @throws IllegalArgumentException if the round is less than 1
     */
    public EventStreamRoundLowerBound(final long round) {
        if (round < 1) {
            throw new IllegalArgumentException("round must be >= 1");
        }
        this.round = round;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final CesEvent consensusEvent) {
        return Long.compare(consensusEvent.getRoundReceived(), round);
    }

    /**
     * get the round of the lower bound.
     *
     * @return the round of the lower bound.
     */
    public long getRound() {
        return round;
    }
}
