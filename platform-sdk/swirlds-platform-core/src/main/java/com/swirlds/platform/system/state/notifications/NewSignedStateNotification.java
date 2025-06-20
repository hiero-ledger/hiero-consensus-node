// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import java.time.Instant;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * A {@link Notification Notification} that a new signed state has been completed. Not
 * guaranteed to be called for every round, and not guaranteed to be called in order. State is guaranteed to hold a
 * reservation until callback completes.
 */
public class NewSignedStateNotification extends AbstractNotification {

    private final MerkleNodeState state;
    private final long round;
    private final Instant consensusTimestamp;

    /**
     * Create a notification for a newly signed state.
     *
     * @param state        the swirld state from the round that is now fully signed
     * @param round              the round that is now fully signed
     * @param consensusTimestamp the consensus timestamp of the round that is now fully signed
     */
    public NewSignedStateNotification(final MerkleNodeState state, final long round, final Instant consensusTimestamp) {
        this.state = state;
        this.round = round;
        this.consensusTimestamp = consensusTimestamp;
    }

    /**
     * Get the swirld state from the round that is now fully signed. Guaranteed to hold a reservation in the scope of
     * this notification.
     */
    @SuppressWarnings("unchecked")
    public <T extends State> T getState() {
        return (T) state;
    }

    /**
     * Get The round that is now fully signed.
     */
    public long getRound() {
        return round;
    }

    /**
     * Get the consensus timestamp of the round that is now fully signed.
     */
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }
}
