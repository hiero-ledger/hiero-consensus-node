// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Specifies the option for buffering future events with the {@link FutureEventBuffer}.
 */
public enum FutureEventBufferingOption {
    /**
     * Buffer events that are greater the pending consensus round.
     */
    PENDING_CONSENSUS_ROUND,
    /**
     * Buffer events that are greater the desired event birth round.
     */
    EVENT_BIRTH_ROUND;

    /**
     * Returns the oldest birth round to buffer for the given event window.
     *
     * @param eventWindow the event window
     * @return the oldest round to buffer
     */
    public long getOldestRoundToBuffer(@NonNull final EventWindow eventWindow) {
        return switch (this) {
            case PENDING_CONSENSUS_ROUND -> eventWindow.getPendingConsensusRound() + 1;
            case EVENT_BIRTH_ROUND -> eventWindow.newEventBirthRound() + 1;
        };
    }

    /**
     * Returns the maximum birth round for events that can be released from the buffer.
     *
     * @param eventWindow the event window
     * @return the maximum releasable round
     */
    public long getMaximumReleasableRound(@NonNull final EventWindow eventWindow) {
        return switch (this) {
            case PENDING_CONSENSUS_ROUND -> eventWindow.getPendingConsensusRound();
            case EVENT_BIRTH_ROUND -> eventWindow.newEventBirthRound();
        };
    }
}
