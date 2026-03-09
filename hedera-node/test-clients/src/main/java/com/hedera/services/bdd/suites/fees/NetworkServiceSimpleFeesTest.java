// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests for simple fee calculations in the Network Admin service
 * (GetVersionInfo, TransactionGetRecord, TransactionGetReceipt).
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
public class NetworkServiceSimpleFeesTest {
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    // Base fees from the simple fee schedule
    private static final double BASE_FEE_GET_VERSION_INFO = 0.0001;
    private static final double BASE_FEE_TRANSACTION_GET_RECORD = 0.0001;
    private static final long EXPECTED_NODE_PAYMENT_TINYCENTS = 84L;

    @HapiTest
    @DisplayName("USD base fee as expected for GetVersionInfo query")
    final Stream<DynamicTest> getVersionInfoBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                getVersionInfo().signedBy(BOB).payingWith(BOB).via("versionInfo"),
                validateChargedUsdForQueries("versionInfo", BASE_FEE_GET_VERSION_INFO, 1.0),
                validateNodePaymentAmountForQuery("versionInfo", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for TransactionGetRecord query")
    final Stream<DynamicTest> transactionGetRecordBaseUSDFee() {
        final var createTxn = "createTxn";
        final var recordQuery = "recordQuery";

        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(ALICE).balance(ONE_BILLION_HBARS),
                cryptoCreate(BOB)
                        .balance(ONE_HUNDRED_HBARS)
                        .signedBy(ALICE)
                        .payingWith(ALICE)
                        .via(createTxn),
                getTxnRecord(createTxn).signedBy(BOB).payingWith(BOB).via(recordQuery),
                validateChargedUsdForQueries(recordQuery, BASE_FEE_TRANSACTION_GET_RECORD, 1.0),
                validateNodePaymentAmountForQuery(recordQuery, EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("transaction get record - invalid account in txn id fails - no fee charged")
    final Stream<DynamicTest> transactionGetRecordInvalidAccountFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        final var now = Instant.now();
        final var invalidTxnId = TransactionID.newBuilder()
                .setAccountID(AccountID.newBuilder().setAccountNum(99999999L).build())
                .setTransactionValidStart(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .build();

        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(ALICE).exposingBalanceTo(initialBalance::set),
                getTxnRecord(invalidTxnId).payingWith(ALICE).hasAnswerOnlyPrecheck(RECORD_NOT_FOUND),
                getAccountBalance(ALICE).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }
}
