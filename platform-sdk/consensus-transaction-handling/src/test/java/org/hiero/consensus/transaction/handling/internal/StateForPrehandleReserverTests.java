// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.junit.jupiter.api.Test;

class StateForPrehandleReserverTests {

    @Test
    void managesInputAndOutputReservations() {
        final String reservationReason = "state for prehandle test";
        final ReservedSignedState inputReservation = mock(ReservedSignedState.class);
        final ReservedSignedState outputReservation = mock(ReservedSignedState.class);
        when(inputReservation.getAndReserve(reservationReason)).thenReturn(outputReservation);

        final TransactionHandlerResult result =
                new TransactionHandlerResult(null, inputReservation, new ConcurrentLinkedQueue<>());
        final StateForPrehandleReserver reserver = new StateForPrehandleReserver(reservationReason);

        assertSame(outputReservation, reserver.transform(result));
        reserver.inputCleanup(result);
        reserver.outputCleanup(outputReservation);

        verify(inputReservation).getAndReserve(reservationReason);
        verify(inputReservation).close();
        verify(outputReservation).close();
    }
}
