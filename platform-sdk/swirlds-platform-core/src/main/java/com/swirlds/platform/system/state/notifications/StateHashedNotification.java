// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.notification.AbstractNotification;
import com.swirlds.common.notification.Notification;
import com.swirlds.platform.wiring.components.StateAndRound;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link Notification} that a state hash has been computed.
 */
public class StateHashedNotification extends AbstractNotification {
    private final long roundNumber;
    private final Hash hash;

    /**
     * Create a notification for a newly hashed state.
     * @param stateAndRound the state and round that is now hashed
     * @return a new notification
     */
    public static StateHashedNotification from(@NonNull final StateAndRound stateAndRound) {
        try (final var state = stateAndRound.reservedSignedState()) {
            return new StateHashedNotification(
                    stateAndRound.round().getRoundNum(),
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
