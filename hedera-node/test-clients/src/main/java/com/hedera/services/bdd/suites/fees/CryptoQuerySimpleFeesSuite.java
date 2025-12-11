// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.Map;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

/**
 * Test suite for Crypto Query operations with simple fees (CryptoGetInfo, CryptoGetAccountRecords).
 * Per HIP-1261, queries use simple fee calculation when fees.simpleFeesEnabled is true.
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoQuerySimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String TEST_ACCOUNT = "testAccount";
    private static final double CRYPTO_GET_INFO_USD = 0.0001;
    private static final double CRYPTO_GET_ACCOUNT_RECORDS_USD = 0.0001;

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
                validateChargedUsd("getInfoQuery", CRYPTO_GET_INFO_USD));
    }

    @HapiTest
    @DisplayName("crypto get info with token associations")
    final Stream<DynamicTest> cryptoGetInfoWithTokenAssociations() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT).payingWith(PAYER),
                getAccountInfo(TEST_ACCOUNT).payingWith(PAYER).via("getInfoWithTokensQuery"),
                validateChargedUsd("getInfoWithTokensQuery", CRYPTO_GET_INFO_USD));
    }

    @HapiTest
    @DisplayName("crypto get account records simple fee")
    final Stream<DynamicTest> cryptoGetAccountRecordsSimpleFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT).payingWith(PAYER),
                cryptoTransfer(tinyBarsFromTo(PAYER, TEST_ACCOUNT, 1000L)).payingWith(PAYER),
                getAccountRecords(TEST_ACCOUNT).payingWith(PAYER).via("getRecordsQuery"),
                validateChargedUsd("getRecordsQuery", CRYPTO_GET_ACCOUNT_RECORDS_USD));
    }

    @HapiTest
    @DisplayName("crypto get account balance is free")
    final Stream<DynamicTest> cryptoGetAccountBalanceIsFree() {
        return hapiTest(
                getAccountBalance(TEST_ACCOUNT).via("accountBalanceQuery"),
                validateChargedUsd("accountBalanceQuery", 0));
    }

    @HapiTest
    @DisplayName("crypto get info multiple queries")
    final Stream<DynamicTest> cryptoGetInfoMultipleQueries() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TEST_ACCOUNT).payingWith(PAYER),
                cryptoCreate("account2").payingWith(PAYER),
                // Multiple queries should each charge the same fee
                getAccountInfo(TEST_ACCOUNT).payingWith(PAYER).via("getInfoQuery1"),
                getAccountInfo("account2").payingWith(PAYER).via("getInfoQuery2"),
                validateChargedUsd("getInfoQuery1", CRYPTO_GET_INFO_USD),
                validateChargedUsd("getInfoQuery2", CRYPTO_GET_INFO_USD));
    }
}
