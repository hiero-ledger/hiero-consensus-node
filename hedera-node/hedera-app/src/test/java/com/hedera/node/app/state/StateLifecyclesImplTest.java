// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.Hedera;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.events.Event;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import java.util.function.Consumer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateLifecyclesImplTest extends MerkleTestBase {
    @Mock
    private Hedera hedera;

    @Mock
    private Event event;

    @Mock
    private Round round;

    @Mock
    private Platform platform;

    @Mock
    private PlatformContext platformContext;

    @Mock
    private MerkleNodeState merkleStateRoot;

    private StateLifecyclesImpl subject;

    @BeforeEach
    void setUp() {
        subject = new StateLifecyclesImpl(hedera);
    }

    @Test
    void delegatesOnPreHandle() {
        final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback = txns -> {};
        subject.onPreHandle(event, merkleStateRoot, callback);

        verify(hedera).onPreHandle(event, merkleStateRoot, callback);
    }

    @Test
    void delegatesOnHandleConsensusRound() {
        final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback = txns -> {};
        subject.onHandleConsensusRound(round, merkleStateRoot, callback);

        verify(hedera).onHandleConsensusRound(round, merkleStateRoot, callback);
    }

    @Test
    void delegatesOnSealConsensusRound() {
        given(hedera.onSealConsensusRound(round, merkleStateRoot)).willReturn(true);

        // Assert the expected return
        final boolean result = subject.onSealConsensusRound(round, merkleStateRoot);
        Assertions.assertThat(result).isTrue();

        // And verify the delegation
        verify(hedera).onSealConsensusRound(round, merkleStateRoot);
    }

    @Test
    void delegatesOnStateInitialized() {
        subject.onStateInitialized(merkleStateRoot, platform, InitTrigger.GENESIS, null);

        verify(hedera).onStateInitialized(merkleStateRoot, platform, InitTrigger.GENESIS);
    }

    @Test
    void onUpdateWeightIsNoop() {
        assertDoesNotThrow(() -> subject.onUpdateWeight(merkleStateRoot, mock(AddressBook.class), platformContext));
    }
}
