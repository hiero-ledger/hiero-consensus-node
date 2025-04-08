// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.app.throttle.NetworkUtilizationManagerImpl;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class NetworkUtilizationManagerImplTest {
    private final Instant consensusNow = Instant.ofEpochSecond(1_234_567L, 123);

    private final DeterministicThrottle throttle = DeterministicThrottle.withTpsAndBurstPeriodMsNamed(500, 10, "test");
    private final LeakyBucketDeterministicThrottle gasThrottle = new LeakyBucketDeterministicThrottle(100, "Gas");

    @LoggingSubject
    private NetworkUtilizationManagerImpl subject;

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    private ThrottleAccumulator throttleAccumulator;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private TransactionInfo transactionInfo;

    @Mock
    private State state;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableSingletonState readableThrottleUsageSnapshotsState;

    @Mock
    private ReadableSingletonState readableCongestionLevelsStartsState;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState writableThrottleUsageSnapshotsState;

    @Mock
    private WritableSingletonState writableCongestionLevelsStartsState;

    @BeforeEach
    void setUp() {
        subject = new NetworkUtilizationManagerImpl(throttleAccumulator, congestionMultipliers);
    }

    @Test
    void verifyTrackTxn() {
        // when
        subject.trackTxn(transactionInfo, consensusNow, state);

        // then
        verify(throttleAccumulator).checkAndEnforceThrottle(transactionInfo, consensusNow, state);
        verify(congestionMultipliers).updateMultiplier(consensusNow);
    }

    @Test
    void verifyTrackFeePayments() {
        // given
        final var expectedTxnToBeChargedFor = new TransactionInfo(
                Transaction.DEFAULT,
                TransactionBody.DEFAULT,
                TransactionID.DEFAULT,
                AccountID.DEFAULT,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                CRYPTO_TRANSFER,
                null);

        // when
        subject.trackFeePayments(consensusNow, state);

        // then
        verify(throttleAccumulator).checkAndEnforceThrottle(expectedTxnToBeChargedFor, consensusNow, state);
        verify(congestionMultipliers).updateMultiplier(consensusNow);
    }
}
