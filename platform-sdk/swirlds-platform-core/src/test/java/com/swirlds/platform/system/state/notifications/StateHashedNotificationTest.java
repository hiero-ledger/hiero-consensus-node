// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import static org.hiero.base.crypto.Cryptography.DEFAULT_DIGEST_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.swirlds.state.merkle.VirtualMapState;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateHashedNotificationTest {
    private static final long ROUND = 123L;
    private static final Hash HASH = new Hash(new byte[DEFAULT_DIGEST_TYPE.digestLength()], DEFAULT_DIGEST_TYPE);

    @Mock
    private VirtualMapState merkleRoot;

    @Mock
    private SignedState signedState;

    @Mock
    private ReservedSignedState reservedSignedState;

    @Test
    void factoryWorksAsExpected() {
        given(reservedSignedState.get()).willReturn(signedState);
        given(signedState.getState()).willReturn(merkleRoot);
        given(signedState.getRound()).willReturn(ROUND);
        given(merkleRoot.getHash()).willReturn(HASH);

        final var notification = StateHashedNotification.from(reservedSignedState);

        assertEquals(ROUND, notification.round());
        assertEquals(HASH, notification.hash());
    }
}
