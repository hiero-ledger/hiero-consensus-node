// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.platform.state.MerkleNodeState;
import java.time.Instant;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * A {@link Notification Notification} that a new signed state has been created as a
 * result of the event recovery process.
 * <p>
 * This notification is sent once during event recovery for the resulting recovered state.
 */
public class NewRecoveredStateNotification extends AbstractNotification {

    private final MerkleNodeState state;
    private final long round;
    private final Instant consensusTimestamp;

    /**
     * Create a notification for a state created as a result of event recovery.
     *
     * @param state        the swirld state from the recovered state
     * @param round              the round of the recovered state
     * @param consensusTimestamp the consensus timestamp of the recovered state round
     */
    public NewRecoveredStateNotification(
            final MerkleNodeState state, final long round, final Instant consensusTimestamp) {
        this.state = state;
        this.round = round;
        this.consensusTimestamp = consensusTimestamp;
    }

    /**
     * Get the state from the recovered state. Guaranteed to hold a reservation in the scope of this
     * notification.
     */
    public MerkleNodeState getState() {
        return state;
    }

    /**
     * Get The round of the recovered state.
     */
    public long getRound() {
        return round;
    }

    /**
     * Get the consensus timestamp of recovered state round.
     */
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }
}
