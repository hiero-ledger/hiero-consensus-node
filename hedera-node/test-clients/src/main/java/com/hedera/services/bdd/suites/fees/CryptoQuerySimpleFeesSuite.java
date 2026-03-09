// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for Crypto Query operations with simple fees (CryptoGetInfo, CryptoGetAccountRecords).
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoQuerySimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String TEST_ACCOUNT = "testAccount";
    private static final double CRYPTO_GET_INFO_USD = 0.0001;
    private static final double CRYPTO_GET_ACCOUNT_RECORDS_USD = 0.0001;
    private static final long EXPECTED_NODE_PAYMENT_TINYCENTS = 84L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @HapiTest
    @DisplayName("crypto get info simple fee")
    final Stream<DynamicTest> cryptoGetInfoSimpleFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT).payingWith(PAYER),
                getAccountInfo(TEST_ACCOUNT).payingWith(PAYER).via("getInfoQuery"),
                validateChargedUsdForQueries("getInfoQuery", CRYPTO_GET_INFO_USD, 1.0),
                validateNodePaymentAmountForQuery("getInfoQuery", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("crypto get info with token associations")
    final Stream<DynamicTest> cryptoGetInfoWithTokenAssociations() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT).payingWith(PAYER),
                getAccountInfo(TEST_ACCOUNT).payingWith(PAYER).via("getInfoWithTokensQuery"),
                validateChargedUsdForQueries("getInfoWithTokensQuery", CRYPTO_GET_INFO_USD, 1.0),
                validateNodePaymentAmountForQuery("getInfoWithTokensQuery", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("crypto get account records simple fee")
    final Stream<DynamicTest> cryptoGetAccountRecordsSimpleFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT).payingWith(PAYER),
                cryptoTransfer(tinyBarsFromTo(PAYER, TEST_ACCOUNT, 1000L)).payingWith(PAYER),
                getAccountRecords(TEST_ACCOUNT).payingWith(PAYER).via("getRecordsQuery"),
                validateChargedUsdForQueries("getRecordsQuery", CRYPTO_GET_ACCOUNT_RECORDS_USD, 1.0),
                validateNodePaymentAmountForQuery("getRecordsQuery", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("crypto get info multiple queries")
    final Stream<DynamicTest> cryptoGetInfoMultipleQueries() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT),
                cryptoCreate("account2").via("createAccount2"),
                // Multiple queries should each charge the same fee
                getAccountInfo(TEST_ACCOUNT).payingWith(PAYER).via("getInfoQuery1"),
                getAccountInfo("account2").payingWith(PAYER).via("getInfoQuery2"),
                getTxnRecord("createAccount2").payingWith(PAYER),
                sleepFor(5000),
                getAccountRecords(PAYER).payingWith(GENESIS).logged(),
                validateChargedUsdForQueries("getInfoQuery1", CRYPTO_GET_INFO_USD, 1.0),
                validateChargedUsdForQueries("getInfoQuery2", CRYPTO_GET_INFO_USD, 1.0),
                validateNodePaymentAmountForQuery("getInfoQuery1", EXPECTED_NODE_PAYMENT_TINYCENTS),
                validateNodePaymentAmountForQuery("getInfoQuery2", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("crypto get info - invalid account fails - no fee charged")
    final Stream<DynamicTest> cryptoGetInfoInvalidAccountFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                getAccountInfo("0.0.99999999").payingWith(PAYER).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }

    @HapiTest
    @DisplayName("crypto get account records - invalid account fails - no fee charged")
    final Stream<DynamicTest> cryptoGetAccountRecordsInvalidAccountFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                getAccountRecords("0.0.99999999").payingWith(PAYER).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID),
                getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }
}
