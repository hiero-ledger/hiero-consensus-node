// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class SignedStateNexusTest {
    private static Stream<SignedStateNexus> allInstances() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        return Stream.concat(raceConditionInstances(), Stream.of(new DefaultLatestCompleteStateNexus(platformContext)));
    }

    private static Stream<SignedStateNexus> raceConditionInstances() {
        return Stream.of(new LockFreeStateNexus(), new EmergencyStateNexus());
    }

    @BeforeEach
    void setUp() {
        MerkleDb.resetDefaultInstancePath();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @ParameterizedTest
    @MethodSource("allInstances")
    void basicUsage(@NonNull final SignedStateNexus nexus) {
        final int round = 123;
        final ReservedSignedState original =
                new RandomSignedStateGenerator().setRound(round).build().reserve("test");

        assertNull(nexus.getState("reason"), "Should be null when initialized");
        assertEquals(ConsensusConstants.ROUND_UNDEFINED, nexus.getRound(), "Should be undefined when initialized");
        nexus.setState(original);

        try (final ReservedSignedState get = nexus.getState("reason")) {
            assertNotNull(get, "Should not be null");
            assertNotSame(original, get, "Should be a different instance");
        }
        assertEquals(round, nexus.getRound(), "Should be set to the round of the state");

        assertFalse(original.isClosed(), "Should not be closed since its still held by the nexus");
        nexus.setState(realState());
        assertTrue(original.isClosed(), "Should be closed once its replaced");

        nexus.clear();
        assertNull(nexus.getState("reason"));
        assertEquals(ConsensusConstants.ROUND_UNDEFINED, nexus.getRound(), "Should be undefined when cleared");
    }

    @ParameterizedTest
    @MethodSource("allInstances")
    void closedStateTest(@NonNull final SignedStateNexus nexus) {
        final ReservedSignedState reservedSignedState = realState();
        nexus.setState(reservedSignedState);
        reservedSignedState.close();
        assertNull(
                nexus.getState("reason"), "The nexus has a state, but it cannot be reserved, so it should return null");
    }

    /**
     * Tests a race condition where the state is replaced while a thread trying to reserve it. In this case, the nexus
     * should reserve the replacement state instead.
     */
    @ParameterizedTest
    @MethodSource("raceConditionInstances")
    void raceConditionTest(@NonNull final SignedStateNexus nexus) throws InterruptedException {
        final ReservedSignedState state1 = mockState();
        final CountDownLatch unblockThread = new CountDownLatch(1);
        final CountDownLatch threadWaiting = new CountDownLatch(1);
        Mockito.when(state1.tryGetAndReserve(Mockito.any())).then(i -> {
            threadWaiting.countDown();
            unblockThread.await();
            return null;
        });
        final ReservedSignedState state2 = mockState();
        final ReservedSignedState state2child = mockState();
        Mockito.when(state2.tryGetAndReserve(Mockito.any())).thenReturn(state2child);

        final CountDownLatch threadDone = new CountDownLatch(1);
        final AtomicReference<ReservedSignedState> threadGetResult = new AtomicReference<>();

        // the nexus should have state1 initially
        nexus.setState(state1);
        // the background thread will try to reserve state1, but will be blocked the unblockThread latch
        new Thread(() -> {
                    threadGetResult.set(nexus.getState("reason"));
                    threadDone.countDown();
                })
                .start();

        // wait for the thread to start trying to reserve the state, fail if it takes too long
        assertTrue(threadWaiting.await(5, TimeUnit.SECONDS), "The thread should be waiting for the state");
        // while the thread is waiting to reserve state1, replace it with state2
        nexus.setState(state2);
        // unblock the thread
        // the nexus should then see that it cannot reserve state1, and that state1 is no longer the current state
        // it should then reserve state2 and set the threadGetResult variable
        unblockThread.countDown();
        // wait for the thread to finish, fail if it takes too long
        assertTrue(threadDone.await(5, TimeUnit.SECONDS), "The thread should have finished");
        // check that the nexus returned the child of state2
        assertSame(state2child, threadGetResult.get(), "The nexus should have returned the child of state2");
    }

    private static ReservedSignedState mockState() {
        final ReservedSignedState state = Mockito.mock(ReservedSignedState.class);
        MerkleDb.resetDefaultInstancePath();
        final SignedState ss = new RandomSignedStateGenerator().build();
        Mockito.when(state.get()).thenReturn(ss);
        return state;
    }

    private static ReservedSignedState realState() {
        MerkleDb.resetDefaultInstancePath();
        return new RandomSignedStateGenerator().build().reserve("test");
    }
}
