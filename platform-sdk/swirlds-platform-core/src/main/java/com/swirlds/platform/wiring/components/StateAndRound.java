// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring.components;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Queue;

/**
 * Contains a reserved signed state, and the consensus round which caused the state to be created
 *
 * @param reservedSignedState the state may null, if the round is not sealed yet
 * @param round               the round that caused the state to be created
 * @param systemTransactions  the system transactions that were included in the round
 */
public record StateAndRound(
        @Nullable ReservedSignedState reservedSignedState,
        @NonNull ConsensusRound round,
        @NonNull Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions) {
    /**
     * Make an additional reservation on the reserved signed state
     *
     * @param reservationReason the reason for the reservation
     * @return a copy of this object, which has its own new reservation on the state
     */
    @NonNull
    public StateAndRound makeAdditionalReservation(@NonNull final String reservationReason) {
        Objects.requireNonNull(reservedSignedState, "reservedSignedState cannot be null");
        return new StateAndRound(reservedSignedState.getAndReserve(reservationReason), round, systemTransactions);
    }
}
