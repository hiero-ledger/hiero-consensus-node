// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * A {@link Notification} that a state hash has been computed.
 */
public class StateHashedNotification extends AbstractNotification {
    private final long roundNumber;
    private final Hash hash;

    /**
     * Create a notification for a newly hashed state.
     * @param state the state that is now hashed
     * @return a new notification
     */
    public static StateHashedNotification from(@NonNull final ReservedSignedState state) {
        try (state) {
            return new StateHashedNotification(
                    state.get().getRound(),
                    requireNonNull(state.get().getState().getHash()));
        }
    }

    public StateHashedNotification(final long roundNumber, @NonNull final Hash hash) {
        this.roundNumber = roundNumber;
        this.hash = requireNonNull(hash);
    }

    public long round() {
        return roundNumber;
    }

    public @NonNull Hash hash() {
        return hash;
    }
}
