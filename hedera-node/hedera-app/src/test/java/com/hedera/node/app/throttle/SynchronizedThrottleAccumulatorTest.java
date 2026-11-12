// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.workflows.TransactionInfo;
import com.swirlds.state.State;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SynchronizedThrottleAccumulatorTest {

    @Mock
    private ThrottleAccumulator throttleAccumulator;

    @Mock
    private TransactionInfo transactionInfo;

    private final InstantSource instantSource = InstantSource.system();

    SynchronizedThrottleAccumulator subject;

    @BeforeEach
    void setUp() {
        subject = new SynchronizedThrottleAccumulator(instantSource, throttleAccumulator);
    }

    @Test
    void verifyCheckAndEnforceThrottleIsCalled() {
        // given
        final var state = mock(State.class);
        final List<ThrottleUsage> usages = new ArrayList<>();

        // when
        subject.shouldThrottle(transactionInfo, state, usages);

        // then
        verify(throttleAccumulator, times(1))
                .checkAndEnforceThrottle(eq(transactionInfo), any(), eq(state), eq(usages), eq(false));
    }

    @Test
    void verifyCheckAndEnforceThrottleQueryIsCalled() {
        // given
        final var query = mock(Query.class);
        final var state = mock(State.class);
        final var accountID = mock(AccountID.class);

        // when
        subject.shouldThrottle(HederaFunctionality.CONTRACT_CREATE, query, state, accountID);

        // then
        verify(throttleAccumulator, times(1))
                .checkAndEnforceThrottle(
                        eq(HederaFunctionality.CONTRACT_CREATE), any(), eq(query), eq(state), eq(accountID));
    }

    @Test
    void decisionsAreMadeHoldingTheMonitorOfTheIngestAccumulator() {
        final var heldForTransaction = new AtomicBoolean();
        final var heldForQuery = new AtomicBoolean();
        final var heldForUtilization = new AtomicBoolean();
        final var state = mock(State.class);
        final var query = mock(Query.class);
        final var accountID = mock(AccountID.class);
        final List<ThrottleUsage> usages = new ArrayList<>();
        when(throttleAccumulator.checkAndEnforceThrottle(eq(transactionInfo), any(), eq(state), eq(usages), eq(false)))
                .thenAnswer(invocation -> {
                    heldForTransaction.set(Thread.holdsLock(throttleAccumulator));
                    return false;
                });
        when(throttleAccumulator.checkAndEnforceThrottle(
                        eq(HederaFunctionality.CONTRACT_CREATE), any(), eq(query), eq(state), eq(accountID)))
                .thenAnswer(invocation -> {
                    heldForQuery.set(Thread.holdsLock(throttleAccumulator));
                    return false;
                });
        when(throttleAccumulator.getHighVolumeThrottleInstantaneousUtilizationBps(
                        eq(HederaFunctionality.CRYPTO_CREATE), any()))
                .thenAnswer(invocation -> {
                    heldForUtilization.set(Thread.holdsLock(throttleAccumulator));
                    return 0;
                });

        subject.shouldThrottle(transactionInfo, state, usages);
        subject.shouldThrottle(HederaFunctionality.CONTRACT_CREATE, query, state, accountID);
        subject.getHighVolumeThrottleUtilization(HederaFunctionality.CRYPTO_CREATE);

        assertTrue(heldForTransaction.get());
        assertTrue(heldForQuery.get());
        assertTrue(heldForUtilization.get());
    }
}
