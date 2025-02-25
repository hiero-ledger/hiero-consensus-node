// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.schedule;

import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.schedule.entities.ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RICH_INSTANT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.KEY_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_STATE_PROOF;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleGetInfoQuery;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
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
            .setTransactionFee(1_234_567L)
            .setCryptoDelete(CryptoDeleteTransactionBody.newBuilder().setDeleteAccountID(payer))
            .build();

    private final SchedulableTransactionBody scheduledTxnWithContractCall = SchedulableTransactionBody.newBuilder()
            .setTransactionFee(1_234_567L)
            .setContractCall(ContractCallTransactionBody.newBuilder())
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
        final var expectedTxBytes = scheduledTxn.getSerializedSize()
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
        final var expectedTxBytes = scheduledTxnWithContractCall.getSerializedSize()
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
                .setHeader(QueryHeader.newBuilder()
                        .setResponseType(ANSWER_STATE_PROOF)
                        .build())
                .setScheduleID(id)
                .build();
        return Query.newBuilder().setScheduleGetInfo(op).build();
    }

    private TransactionBody creationTxn(final SchedulableTransactionBody body) {
        return baseTxn().setScheduleCreate(creationOp(body)).build();
    }

    private TransactionBody deletionTxn() {
        return baseTxn().setScheduleDelete(deletionOp()).build();
    }

    private TransactionBody signingTxn() {
        return baseTxn().setScheduleSign(signingOp()).build();
    }

    private TransactionBody.Builder baseTxn() {
        return TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now))
                        .build());
    }

    private ScheduleCreateTransactionBody creationOp(final SchedulableTransactionBody body) {
        return ScheduleCreateTransactionBody.newBuilder()
                .setMemo(memo)
                .setAdminKey(adminKey)
                .setPayerAccountID(payer)
                .setScheduledTransactionBody(body)
                .build();
    }

    private ScheduleDeleteTransactionBody deletionOp() {
        return ScheduleDeleteTransactionBody.newBuilder().setScheduleID(id).build();
    }

    private ScheduleSignTransactionBody signingOp() {
        return ScheduleSignTransactionBody.newBuilder().setScheduleID(id).build();
    }
}
