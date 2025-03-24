// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import java.time.Instant;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * Class that provides {@link Notification} when reconnect completes
 */
public class ReconnectCompleteNotification extends AbstractNotification {

    private long roundNumber;
    private Instant consensusTimestamp;
    private MerkleNodeState state;

    public ReconnectCompleteNotification(
            final long roundNumber, final Instant consensusTimestamp, final MerkleNodeState state) {
        this.roundNumber = roundNumber;
        this.consensusTimestamp = consensusTimestamp;
        this.state = state;
    }

    /**
     * get round number from the {@link State}
     *
     * @return round number
     */
    public long getRoundNumber() {
        return roundNumber;
    }

    /**
     * The last consensus timestamp handled before the state was signed to the callback
     *
     * @return last consensus timestamp
     */
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }

    /**
     * get the {@link State} instance
     *
     * @return State
     */
    public State getState() {
        return state;
    }
}
