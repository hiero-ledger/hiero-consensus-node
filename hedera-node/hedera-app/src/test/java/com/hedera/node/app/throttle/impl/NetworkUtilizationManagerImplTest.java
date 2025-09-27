// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.throttle.NetworkUtilizationManagerImpl;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleResult;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NetworkUtilizationManagerImplTest {
    private final Instant consensusNow = Instant.ofEpochSecond(1_234_567L, 123);

    private NetworkUtilizationManagerImpl subject;

    @Mock
    private ThrottleAccumulator throttleAccumulator;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private TransactionInfo transactionInfo;

    @Mock
    private State state;

    @BeforeEach
    void setUp() {
        subject = new NetworkUtilizationManagerImpl(throttleAccumulator, congestionMultipliers);
    }

    @Test
    void verifyTrackTxn() throws Exception, PreCheckException {
        // given
        given(throttleAccumulator.checkAndEnforceThrottle(transactionInfo, consensusNow, state, null))
                .willReturn(ThrottleResult.allowed());

        // when
        subject.trackTxn(transactionInfo, consensusNow, state);

        // then
        verify(throttleAccumulator).checkAndEnforceThrottle(transactionInfo, consensusNow, state, null);
        verify(congestionMultipliers).updateMultiplier(consensusNow);
    }

    @Test
    void verifyTrackFeePayments() throws Exception, PreCheckException {
        // given
        final var expectedTxnToBeChargedFor = new TransactionInfo(
                SignedTransaction.DEFAULT,
                TransactionBody.DEFAULT,
                TransactionID.DEFAULT,
                AccountID.DEFAULT,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                CRYPTO_TRANSFER,
                null);

        given(throttleAccumulator.checkAndEnforceThrottle(expectedTxnToBeChargedFor, consensusNow, state, null))
                .willReturn(ThrottleResult.allowed());

        // when
        subject.trackFeePayments(consensusNow, state);

        // then
        verify(throttleAccumulator).checkAndEnforceThrottle(expectedTxnToBeChargedFor, consensusNow, state, null);
        verify(congestionMultipliers).updateMultiplier(consensusNow);
    }
}
