// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.fees.test.IdUtils.asAccount;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_QUERY_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGE_VECTOR;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.NETWORK_RBH;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.NUM_PAYER_KEYS;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RECEIPT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.RECEIPT_STORAGE_TIME_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.test.TxnUtils;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransferList;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SingletonEstimatorUtilsTest {
    private final long maxLifetime = 100 * 365 * 24 * 60 * 60L;
    private final String memo = "abcdefgh";
    private final SigUsage sigUsage = new SigUsage(3, 256, 2);
    private final TransferList transfers = TxnUtils.withAdjustments(
            asAccount("0.0.2"), -2,
            asAccount("0.0.3"), 1,
            asAccount("0.0.4"), 1);

    @Test
    void byteSecondsUsagePeriodsAreCappedAtOneCentury() {
        // given:
        final long oldUsage = 1_234L;
        final long newUsage = 2_345L;
        final long oldLifetime = maxLifetime + 1;
        final long newLifetime = 2 * maxLifetime + 1;
        // and:
        final long cappedChange = maxLifetime * (newUsage - oldUsage);

        // when:
        final var result = ESTIMATOR_UTILS.changeInBsUsage(oldUsage, oldLifetime, newUsage, newLifetime);

        // then:
        assertEquals(cappedChange, result);
    }

    @Test
    void hasExpectedBaseEstimate() {
        // given:
        final TransactionBody txn = TransactionBody.newBuilder()
                .setMemo("You won't want to hear this.")
                .setCryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(AccountAmount.newBuilder()
                                        .setAmount(123L)
                                        .setAccountID(AccountID.newBuilder().setAccountNum(75231)))))
                .build();
        // and:
        final long expectedBpt = ESTIMATOR_UTILS.baseBodyBytes(txn) + sigUsage.sigsSize();
        final long expectedRbs = ESTIMATOR_UTILS.baseRecordBytes(txn) * RECEIPT_STORAGE_TIME_SEC;

        // when:
        final var est = ESTIMATOR_UTILS.baseEstimate(txn, sigUsage);

        // then:
        assertEquals(1L * INT_SIZE, est.base().getBpr());
        assertEquals(sigUsage.numSigs(), est.base().getVpt());
        assertEquals(expectedBpt, est.base().getBpt());
        assertEquals(expectedRbs, est.getRbs());
    }

    @Test
    void hasExpectedBaseNetworkRbs() {
        // expect:
        assertEquals(BASIC_RECEIPT_SIZE * RECEIPT_STORAGE_TIME_SEC, ESTIMATOR_UTILS.baseNetworkRbs());
    }

    @Test
    void partitionsAsExpected() {
        // expect:
        assertEquals(
                A_USAGES_MATRIX,
                ESTIMATOR_UTILS.withDefaultTxnPartitioning(
                        A_USAGE_VECTOR, SubType.DEFAULT, NETWORK_RBH, NUM_PAYER_KEYS));
    }

    @Test
    void partitionsQueriesAsExpected() {
        // expect:
        assertEquals(A_QUERY_USAGES_MATRIX, ESTIMATOR_UTILS.withDefaultQueryPartitioning(A_USAGE_VECTOR));
    }

    @Test
    void understandsStartTime() {
        // given:
        final long now = Instant.now().getEpochSecond();
        final long then = 4688462211L;
        final var txnId = TransactionID.newBuilder()
                .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now));
        final var txn = TransactionBody.newBuilder().setTransactionID(txnId).build();

        // when:
        final long lifetime = ESTIMATOR_UTILS.relativeLifetime(txn, then);

        // then:
        assertEquals(then - now, lifetime);
    }

    @Test
    void getsBaseRecordBytesForNonTransfer() {
        // given:
        final TransactionBody txn = TransactionBody.newBuilder().setMemo(memo).build();
        // and:
        final int expected = FeeBuilder.BASIC_TX_RECORD_SIZE + memo.length();

        // when:
        final int actual = ESTIMATOR_UTILS.baseRecordBytes(txn);

        // then:
        assertEquals(expected, actual);
    }

    @Test
    void getsBaseRecordBytesForTransfer() {
        // given:
        final TransactionBody txn = TransactionBody.newBuilder()
                .setMemo(memo)
                .setCryptoTransfer(CryptoTransferTransactionBody.newBuilder().setTransfers(transfers))
                .build();
        // and:
        final int expected = FeeBuilder.BASIC_TX_RECORD_SIZE
                + memo.length()
                + FeeBuilder.BASIC_ACCOUNT_AMT_SIZE * transfers.getAccountAmountsCount();

        // when:
        final int actual = ESTIMATOR_UTILS.baseRecordBytes(txn);

        // then:
        assertEquals(expected, actual);
    }

    @Test
    void avoidsDegeneracy() {
        // expect:
        assertEquals(0, ESTIMATOR_UTILS.nonDegenerateDiv(0, 60));
        assertEquals(1, ESTIMATOR_UTILS.nonDegenerateDiv(1, 60));
        assertEquals(5, ESTIMATOR_UTILS.nonDegenerateDiv(301, 60));
    }
}
