// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;

/**
 * A lock-free implementation of {@link SignedStateNexus}. This implementation is the most efficient, but it makes it
 * more complicated to add additional functionality to the nexus.
 */
public class LockFreeStateNexus implements SignedStateNexus {
    private final AtomicReference<ReservedSignedState> currentState = new AtomicReference<>();
    private final AtomicLong currentStateRound = new AtomicLong(ConsensusConstants.ROUND_UNDEFINED);

    @Override
    public @Nullable ReservedSignedState getState(@NonNull final String reason) {
        ReservedSignedState state;
        do {
            state = currentState.get();
            if (state == null) {
                return null;
            }

            // between the get method on the atomic reference and the tryGetAndReserve method on the state, the state
            // could have been closed. If this happens, tryGetAndReserve will return null
            final ReservedSignedState newReservation = state.tryGetAndReserve(reason);
            if (newReservation != null) {
                return newReservation;
            }
            // if tryGetAndReserve returned null, then we should check if set() was called in the meantime
            // if yes, then we should try again and reserve the new state
        } while (state != currentState.get());
        // this means we cannot reserve the state we are holding, this is probably an error, since we should hold a
        // reservation on it
        return null;
    }

    @Override
    public void setState(@Nullable final ReservedSignedState reservedSignedState) {
        final ReservedSignedState oldState = currentState.getAndSet(reservedSignedState);
        currentStateRound.set(
                reservedSignedState == null
                        ? ConsensusConstants.ROUND_UNDEFINED
                        : reservedSignedState.get().getRound());
        if (oldState != null) {
            oldState.close();
        }
    }

    @Override
    public long getRound() {
        return currentStateRound.get();
    }
}
