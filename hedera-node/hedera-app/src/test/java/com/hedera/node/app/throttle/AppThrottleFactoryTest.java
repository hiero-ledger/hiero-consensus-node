// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.OpsDurationDeterministicThrottle;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import java.time.Instant;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppThrottleFactoryTest {
    private static final int SPLIT_FACTOR = 7;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(123456, 789);
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(666L).build();
    private static final TransactionInfo TXN_INFO = new TransactionInfo(
            SignedTransaction.DEFAULT,
            TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
                    .build(),
            TransactionID.DEFAULT,
            PAYER_ID,
            SignatureMap.DEFAULT,
            Bytes.EMPTY,
            CRYPTO_TRANSFER,
            null);
    private static final ThrottleUsageSnapshots FAKE_SNAPSHOTS = new ThrottleUsageSnapshots(
            List.of(
                    new ThrottleUsageSnapshot(1L, new Timestamp(234567, 8)),
                    new ThrottleUsageSnapshot(2L, new Timestamp(345678, 9))),
            ThrottleUsageSnapshot.DEFAULT,
            ThrottleUsageSnapshot.DEFAULT);

    @Mock
    private State state;

    @Mock
    private Supplier<Configuration> config;

    @Mock
    private ThrottleAccumulator throttleAccumulator;

    @Mock
    private DeterministicThrottle firstThrottle;

    @Mock
    private DeterministicThrottle lastThrottle;

    @Mock
    private LeakyBucketDeterministicThrottle gasThrottle;

    @Mock
    private LeakyBucketDeterministicThrottle bytesThrottle;

    @Mock
    private OpsDurationDeterministicThrottle opsDurationThrottle;

    @Mock
    private AppThrottleFactory.ThrottleAccumulatorFactory throttleAccumulatorFactory;

    private AppThrottleFactory subject;
    private SemanticVersion softwareVersionFactory;

    @BeforeEach
    void setUp() {
        softwareVersionFactory = SemanticVersion.DEFAULT;
        subject = new AppThrottleFactory(
                config, () -> state, () -> ThrottleDefinitions.DEFAULT, throttleAccumulatorFactory);
    }

    @Test
    void initializesAccumulatorFromCurrentConfigAndGivenDefinitions() throws PreCheckException {
        given(throttleAccumulatorFactory.newThrottleAccumulator(
                        eq(config), argThat((IntSupplier i) -> i.getAsInt() == SPLIT_FACTOR), eq(BACKEND_THROTTLE)))
                .willReturn(throttleAccumulator);
        given(throttleAccumulator.allActiveThrottles()).willReturn(List.of(firstThrottle, lastThrottle));
        given(throttleAccumulator.gasLimitThrottle()).willReturn(gasThrottle);
        given(throttleAccumulator.opsDurationThrottle()).willReturn(opsDurationThrottle);

        final var throttle = subject.newThrottle(SPLIT_FACTOR, FAKE_SNAPSHOTS);

        verify(throttleAccumulator).applyGasConfig();
        verify(throttleAccumulator).rebuildFor(ThrottleDefinitions.DEFAULT);
        verify(firstThrottle).resetUsageTo(FAKE_SNAPSHOTS.tpsThrottles().getFirst());
        verify(lastThrottle).resetUsageTo(FAKE_SNAPSHOTS.tpsThrottles().getLast());
        verify(gasThrottle).resetUsageTo(FAKE_SNAPSHOTS.gasThrottleOrThrow());

        given(throttleAccumulator.checkAndEnforceThrottle(TXN_INFO, CONSENSUS_NOW, state, null))
                .willReturn(ThrottleResult.throttled());
        assertThat(throttle.allow(PAYER_ID, TXN_INFO.txBody(), TXN_INFO.functionality(), CONSENSUS_NOW))
                .isFalse();

        given(firstThrottle.usageSnapshot())
                .willReturn(FAKE_SNAPSHOTS.tpsThrottles().getFirst());
        given(lastThrottle.usageSnapshot())
                .willReturn(FAKE_SNAPSHOTS.tpsThrottles().getLast());
        given(gasThrottle.usageSnapshot()).willReturn(FAKE_SNAPSHOTS.gasThrottleOrThrow());
        given(opsDurationThrottle.usageSnapshot()).willReturn(FAKE_SNAPSHOTS.evmOpsDurationThrottleOrThrow());
        assertEquals(FAKE_SNAPSHOTS, throttle.usageSnapshots());
    }

    @Test
    void throttleAllowRejectsMalformedScheduleCreate() throws Exception {
        // Given a throttle with malformed SCHEDULE_CREATE transaction (missing scheduledTransactionBody)
        final var malformedScheduleCreate = ScheduleCreateTransactionBody.newBuilder()
                .waitForExpiry(false)
                .build(); // Missing scheduledTransactionBody

        final var malformedTxBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .scheduleCreate(malformedScheduleCreate)
                .build();

        // Set up throttle accumulator factory mock
        given(throttleAccumulatorFactory.newThrottleAccumulator(
                        eq(config), argThat((IntSupplier i) -> i.getAsInt() == SPLIT_FACTOR), eq(BACKEND_THROTTLE)))
                .willReturn(throttleAccumulator);

        // Set up mocks
        given(throttleAccumulator.checkAndEnforceThrottle(
                        argThat(txnInfo -> txnInfo.functionality() == SCHEDULE_CREATE),
                        eq(CONSENSUS_NOW),
                        eq(state),
                        eq(null)))
                .willReturn(ThrottleResult.invalid(INVALID_TRANSACTION));

        final var throttle = subject.newThrottle(SPLIT_FACTOR, null);

        // When & Then - Invalid transaction should return false (not allowed)
        final var result = throttle.allow(PAYER_ID, malformedTxBody, SCHEDULE_CREATE, CONSENSUS_NOW);

        assertFalse(result);
    }

    @Test
    void throttleAllowRejectsMalformedTokenMint() throws Exception {
        // Given a throttle with malformed TOKEN_MINT transaction (missing token)
        final var malformedTokenMint =
                TokenMintTransactionBody.newBuilder().amount(100L).build(); // Missing token field

        final var malformedTxBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .tokenMint(malformedTokenMint)
                .build();

        // Set up throttle accumulator factory mock
        given(throttleAccumulatorFactory.newThrottleAccumulator(
                        eq(config), argThat((IntSupplier i) -> i.getAsInt() == SPLIT_FACTOR), eq(BACKEND_THROTTLE)))
                .willReturn(throttleAccumulator);

        // Set up mocks
        given(throttleAccumulator.checkAndEnforceThrottle(
                        argThat(txnInfo -> txnInfo.functionality() == TOKEN_MINT),
                        eq(CONSENSUS_NOW),
                        eq(state),
                        eq(null)))
                .willReturn(ThrottleResult.invalid(INVALID_TRANSACTION));

        final var throttle = subject.newThrottle(SPLIT_FACTOR, null);

        // When & Then - Invalid transaction should return false (not allowed)
        final var result = throttle.allow(PAYER_ID, malformedTxBody, TOKEN_MINT, CONSENSUS_NOW);

        assertFalse(result);
    }

    @Test
    void throttleAllowRejectsMalformedEthereumTransaction() throws Exception {
        // Given a throttle with malformed ETHEREUM_TRANSACTION (empty ethereumData)
        final var malformedEthTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(Bytes.EMPTY) // Empty ethereum data
                .build();

        final var malformedTxBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID).build())
                .ethereumTransaction(malformedEthTxn)
                .build();

        // Set up throttle accumulator factory mock
        given(throttleAccumulatorFactory.newThrottleAccumulator(
                        eq(config), argThat((IntSupplier i) -> i.getAsInt() == SPLIT_FACTOR), eq(BACKEND_THROTTLE)))
                .willReturn(throttleAccumulator);

        // Set up mocks
        given(throttleAccumulator.checkAndEnforceThrottle(
                        argThat(txnInfo -> txnInfo.functionality() == ETHEREUM_TRANSACTION),
                        eq(CONSENSUS_NOW),
                        eq(state),
                        eq(null)))
                .willReturn(ThrottleResult.invalid(INVALID_TRANSACTION));

        final var throttle = subject.newThrottle(SPLIT_FACTOR, null);

        // When & Then - Invalid transaction should return false (not allowed)
        final var result = throttle.allow(PAYER_ID, malformedTxBody, ETHEREUM_TRANSACTION, CONSENSUS_NOW);

        assertFalse(result);
    }
}
