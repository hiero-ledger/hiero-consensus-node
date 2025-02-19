/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
