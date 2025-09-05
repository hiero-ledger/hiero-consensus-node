// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PlatformSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class StateSignerTests {

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    void doNotSignPcesState() {
        final SignedState signedState =
                new RandomSignedStateGenerator().setPcesRound(true).build();

        final PlatformSigner platformSigner = mock(PlatformSigner.class);
        final StateSigner stateSigner = new DefaultStateSigner(platformSigner);

        final ReservedSignedState reservedSignedState = signedState.reserve("test");
        final StateSignatureTransaction signatureTransaction = stateSigner.signState(reservedSignedState);
        assertTrue(reservedSignedState.isClosed());
        assertNull(signatureTransaction);
    }

    @Test
    void signRegularState() {
        final Randotron randotron = Randotron.create();

        final SignedState signedState =
                new RandomSignedStateGenerator().setPcesRound(false).build();

        final PlatformSigner platformSigner = mock(PlatformSigner.class);
        final Signature signature = randotron.nextSignature();
        when(platformSigner.signImmutable(any())).thenReturn(signature.getBytes());

        final StateSigner stateSigner = new DefaultStateSigner(platformSigner);

        final ReservedSignedState reservedSignedState = signedState.reserve("test");
        final StateSignatureTransaction payload = stateSigner.signState(reservedSignedState);
        assertTrue(reservedSignedState.isClosed());
        assertNotNull(payload);
        assertEquals(payload.signature(), signature.getBytes());
    }
}
