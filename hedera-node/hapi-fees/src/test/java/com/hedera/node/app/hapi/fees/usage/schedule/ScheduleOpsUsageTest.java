// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.schedule;

import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.schedule.entities.ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RICH_INSTANT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.KEY_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoQuery;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleOpsUsageTest {
    private final int numSigs = 3;
    private final int sigSize = 144;
    private final int numPayerKeys = 1;
    private final int scheduledTxnIdSize = BASIC_TX_ID_SIZE + BOOL_SIZE;
    private final long now = 1_234_567L;
    private final long lifetimeSecs = 1800L;
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);

    private final Key adminKey = KeyUtils.A_COMPLEX_KEY;
    private final ScheduleID id = IdUtils.asSchedule("0.0.1");
    private final String memo = "This is just a memo?";
    private final AccountID payer = IdUtils.asAccount("0.0.2");
    private final SchedulableTransactionBody scheduledTxn = SchedulableTransactionBody.newBuilder()
            .transactionFee(1_234_567L)
            .cryptoDelete(CryptoDeleteTransactionBody.newBuilder().deleteAccountID(payer))
            .build();

    private final SchedulableTransactionBody scheduledTxnWithContractCall = SchedulableTransactionBody.newBuilder()
            .transactionFee(1_234_567L)
            .contractCall(ContractCallTransactionBody.newBuilder())
            .build();

    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private Function<ResponseType, QueryUsage> queryEstimatorFactory;
    private QueryUsage queryBase;

    private final ScheduleOpsUsage subject = new ScheduleOpsUsage();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        base = mock(TxnUsageEstimator.class);
        given(base.get()).willReturn(A_USAGES_MATRIX);
        given(base.get(SCHEDULE_CREATE_CONTRACT_CALL)).willReturn(A_USAGES_MATRIX);
        queryBase = mock(QueryUsage.class);
        given(queryBase.get()).willReturn(A_USAGES_MATRIX);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
        queryEstimatorFactory = mock(Function.class);
        given(queryEstimatorFactory.apply(ANSWER_STATE_PROOF)).willReturn(queryBase);

        subject.txnEstimateFactory = factory;
        subject.queryEstimateFactory = queryEstimatorFactory;
    }

    @Test
    void estimatesSignAsExpected() {
        // setup:
        final long lifetimeSecs = 1800L;

        // when:
        final var estimate = subject.scheduleSignUsage(signingTxn(), sigUsage, now + lifetimeSecs);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(BASIC_ENTITY_ID_SIZE);
        verify(base).addRbs(2 * KEY_SIZE * lifetimeSecs);
        verify(base).addNetworkRbs(scheduledTxnIdSize * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void estimatesDeleteExpected() {
        // setup:
        final long lifetimeSecs = 1800L;

        // when:
        final var estimate = subject.scheduleDeleteUsage(deletionTxn(), sigUsage, now + lifetimeSecs);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(BASIC_ENTITY_ID_SIZE);
        verify(base).addRbs(BASIC_RICH_INSTANT_SIZE * lifetimeSecs);
    }

    @Test
    void estimatesCreateAsExpected() {
        // given:
        final var createdCtx = ExtantScheduleContext.newBuilder()
                .setAdminKey(adminKey)
                .setMemo(memo)
                .setScheduledTxn(scheduledTxn)
                .setNumSigners(SCHEDULE_ENTITY_SIZES.estimatedScheduleSigs(sigUsage))
                .setResolved(false)
                .build();
        final var expectedRamBytes = createdCtx.nonBaseRb();
        // and:
        final var expectedTxBytes =
                SchedulableTransactionBody.PROTOBUF.toBytes(scheduledTxn).length()
                        + getAccountKeyStorageSize(adminKey)
                        + memo.length()
                        + BASIC_ENTITY_ID_SIZE;

        // when:
        final var estimate = subject.scheduleCreateUsage(creationTxn(scheduledTxn), sigUsage, lifetimeSecs);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(expectedTxBytes);
        verify(base).addRbs(expectedRamBytes * lifetimeSecs);
        verify(base)
                .addNetworkRbs(
                        (BASIC_ENTITY_ID_SIZE + scheduledTxnIdSize) * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void estimatesCreateWithContractCallAsExpected() {
        // given:
        final var createdCtx = ExtantScheduleContext.newBuilder()
                .setAdminKey(adminKey)
                .setMemo(memo)
                .setScheduledTxn(scheduledTxnWithContractCall)
                .setNumSigners(SCHEDULE_ENTITY_SIZES.estimatedScheduleSigs(sigUsage))
                .setResolved(false)
                .build();
        final var expectedRamBytes = createdCtx.nonBaseRb();
        // and:
        final var expectedTxBytes = SchedulableTransactionBody.PROTOBUF
                        .toBytes(scheduledTxnWithContractCall)
                        .length()
                + getAccountKeyStorageSize(adminKey)
                + memo.length()
                + BASIC_ENTITY_ID_SIZE;

        // when:
        final var estimate =
                subject.scheduleCreateUsage(creationTxn(scheduledTxnWithContractCall), sigUsage, lifetimeSecs);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(expectedTxBytes);
        verify(base).addRbs(expectedRamBytes * lifetimeSecs);
        verify(base)
                .addNetworkRbs(
                        (BASIC_ENTITY_ID_SIZE + scheduledTxnIdSize) * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void estimatesGetInfoAsExpected() {
        // given:
        final var ctx = ExtantScheduleContext.newBuilder()
                .setAdminKey(adminKey)
                .setMemo(memo)
                .setNumSigners(2)
                .setResolved(true)
                .setScheduledTxn(scheduledTxn)
                .build();

        // when:
        final var estimate = subject.scheduleInfoUsage(scheduleQuery(), ctx);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(queryBase).addTb(BASIC_ENTITY_ID_SIZE);
        verify(queryBase).addRb(ctx.nonBaseRb());
    }

    private Query scheduleQuery() {
        final var op = ScheduleGetInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder()
                        .responseType(ANSWER_STATE_PROOF)
                        .build())
                .scheduleID(id)
                .build();
        return Query.newBuilder().scheduleGetInfo(op).build();
    }

    private TransactionBody creationTxn(final SchedulableTransactionBody body) {
        return baseTxn().scheduleCreate(creationOp(body)).build();
    }

    private TransactionBody deletionTxn() {
        return baseTxn().scheduleDelete(deletionOp()).build();
    }

    private TransactionBody signingTxn() {
        return baseTxn().scheduleSign(signingOp()).build();
    }

    private TransactionBody.Builder baseTxn() {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now))
                        .build());
    }

    private ScheduleCreateTransactionBody creationOp(final SchedulableTransactionBody body) {
        return ScheduleCreateTransactionBody.newBuilder()
                .memo(memo)
                .adminKey(adminKey)
                .payerAccountID(payer)
                .scheduledTransactionBody(body)
                .build();
    }

    private ScheduleDeleteTransactionBody deletionOp() {
        return ScheduleDeleteTransactionBody.newBuilder().scheduleID(id).build();
    }

    private ScheduleSignTransactionBody signingOp() {
        return ScheduleSignTransactionBody.newBuilder().scheduleID(id).build();
    }
}
