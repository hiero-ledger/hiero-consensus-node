// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import java.time.Instant;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * Class that provides {@link Notification} when state is written to disk
 */
public class StateWriteToDiskCompleteNotification extends AbstractNotification {

    private final long roundNumber;
    private final Instant consensusTimestamp;
    private final boolean isFreezeState;

    public StateWriteToDiskCompleteNotification(
            final long roundNumber, final Instant consensusTimestamp, final boolean isFreezeState) {
        this.roundNumber = roundNumber;
        this.consensusTimestamp = consensusTimestamp;
        this.isFreezeState = isFreezeState;
    }

    /**
     * Gets round number from the state that is written to disk.
     *
     * @return the round number
     */
    public long getRoundNumber() {
        return roundNumber;
    }

    /**
     * Gets the consensus timestamp handled before the state is written to disk.
     *
     * @return the consensus timestamp
     */
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }

    /**
     * Gets whether this is a freeze state
     *
     * @return whether this is a freeze state
     */
    public boolean isFreezeState() {
        return isFreezeState;
    }
}
