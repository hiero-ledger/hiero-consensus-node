// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.crypto.Hash;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.wiring.components.StateAndRound;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateHashedNotificationTest {
    private static final long ROUND = 123L;
    private static final Hash HASH = new Hash(new byte[48]);

    @Mock
    private MerkleNodeState merkleRoot;

    @Mock
    private SignedState signedState;

    @Mock
    private ConsensusRound round;

    @Mock
    private ReservedSignedState reservedSignedState;

    @Mock
    private Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions;

    @Test
    void factoryWorksAsExpected() {
        given(round.getRoundNum()).willReturn(ROUND);
        given(reservedSignedState.get()).willReturn(signedState);
        given(signedState.getState()).willReturn(merkleRoot);
        given(merkleRoot.getHash()).willReturn(HASH);

        final var notification =
                StateHashedNotification.from(new StateAndRound(reservedSignedState, round, systemTransactions));

        assertEquals(ROUND, notification.round());
        assertEquals(HASH, notification.hash());
    }
}
