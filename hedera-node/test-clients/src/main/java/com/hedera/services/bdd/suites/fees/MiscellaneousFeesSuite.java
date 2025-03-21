// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.handleAnyRepeatableQueryPayment;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class MiscellaneousFeesSuite {
    private static final String PRNG_IS_ENABLED = "utilPrng.isEnabled";
    private static final String BOB = "bob";
    private static final String ALICE = "alice";
    private static final double BASE_FEE_MISC_GET_VERSION = 0.0001;
    private static final double BASE_FEE_MISC_PRNG_TRX = 0.001;
    private static final double BASE_FEE_ATOMIC_BATCH = 0.001;
    public static final double BASE_FEE_MISC_GET_TRX_RECORD = 0.0001;
    private static final double EXPECTED_FEE_PRNG_RANGE_TRX = 0.0010010316;

    @HapiTest
    @DisplayName("USD base fee as expected for Prng transaction")
    final Stream<DynamicTest> miscPrngTrxBaseUSDFee() {
        final var baseTxn = "prng";
        final var plusRangeTxn = "prngWithRange";

        return hapiTest(
                overridingAllOf(Map.of(PRNG_IS_ENABLED, "true")),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                hapiPrng().payingWith(BOB).via(baseTxn).blankMemo().logged(),
                getTxnRecord(baseTxn).hasOnlyPseudoRandomBytes().logged(),
                validateChargedUsd(baseTxn, BASE_FEE_MISC_PRNG_TRX),
                hapiPrng(10).payingWith(BOB).via(plusRangeTxn).blankMemo().logged(),
                getTxnRecord(plusRangeTxn).hasOnlyPseudoRandomNumberInRange(10).logged(),
                validateChargedUsd(plusRangeTxn, EXPECTED_FEE_PRNG_RANGE_TRX, 0.5));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    @DisplayName("USD base fee as expected for get version info")
    final Stream<DynamicTest> miscGetInfoBaseUSDFee() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                getVersionInfo().signedBy(BOB).payingWith(BOB).via("versionInfo"),
                handleAnyRepeatableQueryPayment(),
                validateChargedUsd("versionInfo", BASE_FEE_MISC_GET_VERSION));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for get account balance")
    final Stream<DynamicTest> miscGetAccountBalanceBaseUSDFee() {
        return hapiTest(
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).via("createTxn").logged(),
                getReceipt("createTxn").signedBy(BOB).payingWith(BOB),
                // free transaction - verifying that the paying account has the same balance as it was at the beginning
                getAccountBalance(BOB).hasTinyBars(ONE_HUNDRED_HBARS));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for get transaction record")
    final Stream<DynamicTest> miscGetTransactionRecordBaseUSDFee() {
        String baseTransactionGetRecord = "baseTransactionGetRecord";
        String createTxn = "createTxn";
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(ALICE).balance(ONE_BILLION_HBARS),
                cryptoCreate(BOB)
                        .balance(ONE_HUNDRED_HBARS)
                        .signedBy(ALICE)
                        .payingWith(ALICE)
                        .via(createTxn)
                        .logged(),
                getTxnRecord(createTxn).signedBy(BOB).payingWith(BOB).via(baseTransactionGetRecord),
                sleepFor(1000),
                validateChargedUsd(baseTransactionGetRecord, BASE_FEE_MISC_GET_TRX_RECORD));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for atomic batch transaction")
    public Stream<DynamicTest> validateAtomicBatchBaseUSDFee() {
        final var batchOperator = "batchOperator";

        final var innerTxn = cryptoCreate("foo").balance(ONE_HBAR).batchKey(batchOperator);

        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HBAR),
                atomicBatch(innerTxn).payingWith(batchOperator).via("batchTxn"),
                validateChargedUsd("batchTxn", BASE_FEE_ATOMIC_BATCH));
    }
}
