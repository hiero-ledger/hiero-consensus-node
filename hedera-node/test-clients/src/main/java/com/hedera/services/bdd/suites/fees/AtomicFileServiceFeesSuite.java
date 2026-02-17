// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFeeFromBytesFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateInnerTxnFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_APPEND_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_CREATE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_DELETE_BASE_FEE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of FileServiceFeesSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@Tag(ATOMIC_BATCH)
class AtomicFileServiceFeesSuite {
    private static final String MEMO = "Really quite something!";
    private static final String CIVILIAN = "civilian";
    private static final String KEY = "key";
    private static final double BASE_FEE_FILE_CREATE = 0.05;
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @HapiTest
    @DisplayName("USD base fee as expected for file create transaction")
    final Stream<DynamicTest> fileCreateBaseUSDFee() {
        // 90 days considered for base fee
        var contents = "0".repeat(1000).getBytes();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                newKeyNamed(KEY).shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key(KEY).balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                atomicBatch(fileCreate("test")
                                .memo(MEMO)
                                .key("WACL")
                                .contents(contents)
                                .payingWith(CIVILIAN)
                                .via("fileCreateBasic")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        validateInnerTxnFees(
                                "fileCreateBasic",
                                ATOMIC_BATCH,
                                BASE_FEE_FILE_CREATE,
                                FILE_CREATE_BASE_FEE + expectedFeeFromBytesFor(spec, log, "fileCreateBasic"),
                                5.0))));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file update transaction")
    final Stream<DynamicTest> fileUpdateBaseUSDFee() {
        var contents = "0".repeat(1000).getBytes();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                newKeyNamed("key").shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key("key").balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("key", List.of(CIVILIAN)),
                fileCreate("test").key("key").contents("ABC"),
                atomicBatch(fileUpdate("test")
                                .contents(contents)
                                .memo(MEMO)
                                .payingWith(CIVILIAN)
                                .via("fileUpdateBasic")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        validateInnerTxnFees(
                                "fileUpdateBasic",
                                ATOMIC_BATCH,
                                0.05,
                                FILE_APPEND_BASE_FEE + expectedFeeFromBytesFor(spec, log, "fileUpdateBasic"),
                                3))));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file delete transaction")
    final Stream<DynamicTest> fileDeleteBaseUSDFee() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                newKeyNamed("key").shape(KeyShape.SIMPLE),
                cryptoCreate(CIVILIAN).key("key").balance(ONE_HUNDRED_HBARS),
                newKeyListNamed("WACL", List.of(CIVILIAN)),
                fileCreate("test").memo(MEMO).key("WACL").contents("ABC"),
                atomicBatch(fileDelete("test")
                                .blankMemo()
                                .payingWith(CIVILIAN)
                                .via("fileDeleteBasic")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnFees("fileDeleteBasic", ATOMIC_BATCH, 0.00701, FILE_DELETE_BASE_FEE));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for file append transaction")
    @Tag(MATS)
    final Stream<DynamicTest> fileAppendBaseUSDFee() {
        final var civilian = "NonExemptPayer";

        final var baseAppend = "baseAppend";
        final var targetFile = "targetFile";
        final var magicKey = "magicKey";
        final var magicWacl = "magicWacl";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                newKeyNamed(magicKey),
                newKeyListNamed(magicWacl, List.of(magicKey)),
                cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS).key(magicKey),
                fileCreate(targetFile)
                        .key(magicWacl)
                        .lifetime(THREE_MONTHS_IN_SECONDS)
                        .contents("Nothing much!"),
                atomicBatch(fileAppend(targetFile)
                                .signedBy(magicKey)
                                .blankMemo()
                                .content("A".repeat(1000))
                                .payingWith(civilian)
                                .via(baseAppend)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        validateInnerTxnFees(
                                baseAppend,
                                ATOMIC_BATCH,
                                FILE_APPEND_BASE_FEE,
                                FILE_APPEND_BASE_FEE + expectedFeeFromBytesFor(spec, log, baseAppend),
                                5.0))));
    }
}
