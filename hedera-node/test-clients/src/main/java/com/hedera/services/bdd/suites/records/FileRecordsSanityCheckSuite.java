// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.takeBalanceSnapshots;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateRecordTransactionFees;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateTransferListForBalances;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withStrictCostAnswerValidation;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.NODE;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class FileRecordsSanityCheckSuite {
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> fileAppendRecordSanityChecks() {
        return hapiTest(flattened(
                fileCreate("test"),
                takeBalanceSnapshots(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                fileAppend("test").via("txn").fee(95_000_000L),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> fileCreateRecordSanityChecks() {
        return hapiTest(flattened(
                takeBalanceSnapshots(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                fileCreate("test").via("txn"),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    @HapiTest
    final Stream<DynamicTest> fileDeleteRecordSanityChecks() {
        return hapiTest(flattened(
                fileCreate("test"),
                takeBalanceSnapshots(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                fileDelete("test").via("txn"),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    @HapiTest
    final Stream<DynamicTest> fileUpdateRecordSanityChecks() {
        return hapiTest(flattened(
                fileCreate("test"),
                takeBalanceSnapshots(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                fileUpdate("test")
                        .contents("Here are some new contents!")
                        .via("txn")
                        .fee(95_000_000L),
                withStrictCostAnswerValidation(() -> getFileInfo("test").payingWith(EXCHANGE_RATE_CONTROL)),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }
}
