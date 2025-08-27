// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * A wrapper for a signed state that includes an estimate of how much work it will be to compute the hash of the state.
 * This estimate is used by the wiring framework to measure if the {@link com.swirlds.platform.state.hasher.StateHasher}
 * is healthy. This estimate is the number of application transactions that were applied to this state. The hash
 * complexity must always be at least 1.
 *
 * @param reservedSignedState the state to be hashed
 * @param hashComplexity      the estimated complexity of the hash calculation. Minimum value is 1.
 */
public record StateWithHashComplexity(@NonNull ReservedSignedState reservedSignedState, long hashComplexity) {

    public StateWithHashComplexity {
        Objects.requireNonNull(reservedSignedState, "reservedSignedState cannot be null");
        if (hashComplexity < 1) {
            throw new IllegalArgumentException("Hash complexity must be at least 1");
        }
    }

    /**
     * Make an additional reservation on the reserved signed state
     *
     * @param reservationReason the reason for the reservation
     * @return a copy of this object, which has its own new reservation on the state
     */
    @NonNull
    public StateWithHashComplexity makeAdditionalReservation(@NonNull final String reservationReason) {
        return new StateWithHashComplexity(reservedSignedState.getAndReserve(reservationReason), hashComplexity);
    }
}
