// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.MerkleNodeState;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DefaultTransactionPrehandler}
 */
class TransactionPrehandlerTests {
    @Test
    @DisplayName("Normal operation")
    void normalOperation() {
        final Random random = RandomUtils.getRandomPrintSeed();

        final AtomicBoolean returnValidState = new AtomicBoolean(false);
        final AtomicBoolean stateRetrievalAttempted = new AtomicBoolean(false);

        final AtomicBoolean stateClosed = new AtomicBoolean(false);
        final ReservedSignedState state = mock(ReservedSignedState.class);
        doAnswer(invocation -> {
                    assertFalse(stateClosed::get);
                    stateClosed.set(true);
                    return null;
                })
                .when(state)
                .close();

        final SignedState signedState = mock(SignedState.class);
        final MerkleNodeState stateRoot = mock(MerkleNodeState.class);
        when(signedState.getState()).thenReturn(stateRoot);

        final SignedStateNexus latestImmutableStateNexus = mock(SignedStateNexus.class);
        final ConsensusStateEventHandler consensusStateEventHandler = mock(ConsensusStateEventHandler.class);
        // return null until returnValidState is set to true. keep track of when the first state retrieval is attempted,
        // so we can assert that prehandle hasn't happened before the state is available
        when(latestImmutableStateNexus.getState(any())).thenAnswer(i -> {
            stateRetrievalAttempted.set(true);
            return returnValidState.get() ? state : null;
        });

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final TransactionPrehandler transactionPrehandler = new DefaultTransactionPrehandler(
                platformContext, () -> latestImmutableStateNexus.getState("test"), consensusStateEventHandler);

        final PlatformEvent platformEvent = new TestingEventBuilder(random).build();

        final AtomicBoolean prehandleCompleted = new AtomicBoolean(false);
        new Thread(() -> {
                    platformEvent.awaitPrehandleCompletion();
                    prehandleCompleted.set(true);
                })
                .start();

        new Thread(() -> transactionPrehandler.prehandleApplicationTransactions(platformEvent)).start();

        assertEventuallyTrue(stateRetrievalAttempted::get, Duration.ofSeconds(1), "state retrieval wasn't attempted");
        assertFalse(prehandleCompleted::get, "prehandle completed before state was available");
        returnValidState.set(true);

        assertEventuallyTrue(prehandleCompleted::get, Duration.ofSeconds(1), "prehandle didn't complete");
        assertEventuallyTrue(stateClosed::get, Duration.ofSeconds(1), "state wasn't closed");
    }
}
