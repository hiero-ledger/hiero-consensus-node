// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.junit.ContextRequirement.PERMISSION_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.BYTES_4K;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUtf8Bytes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static java.lang.Long.parseLong;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of FileUpdateSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@SuppressWarnings("java:S1192")
public class AtomicFileUpdateSuite {

    private static final String CREATE_TXN = "create";
    public static final String CIVILIAN = "civilian";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    @Tag(MATS)
    final Stream<DynamicTest> apiPermissionsChangeDynamically() {
        final var civilian = CIVILIAN;
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BATCH_OPERATOR),
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1_000_000_000L)),
                cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS),
                getFileContents(API_PERMISSIONS).logged(),
                tokenCreate("poc").payingWith(civilian),
                atomicBatch(fileUpdate(API_PERMISSIONS)
                                .payingWith(ADDRESS_BOOK_CONTROL)
                                .overridingProps(Map.of("tokenCreate", "0-1"))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileContents(API_PERMISSIONS).logged(),
                tokenCreate("poc")
                        .payingWith(civilian)
                        .hasPrecheckFrom(NOT_SUPPORTED, OK)
                        .hasKnownStatus(UNAUTHORIZED),
                atomicBatch(fileUpdate(API_PERMISSIONS)
                                .payingWith(ADDRESS_BOOK_CONTROL)
                                .overridingProps(Map.of("tokenCreate", "0-*"))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                tokenCreate("secondPoc").payingWith(civilian));
    }

    @HapiTest
    final Stream<DynamicTest> updateFeesCompatibleWithCreates() {
        final long origLifetime = 7_200_000L;
        final long extension = 700_000L;
        final byte[] old2k = randomUtf8Bytes(BYTES_4K / 2);
        final byte[] new4k = randomUtf8Bytes(BYTES_4K);
        final byte[] new2k = randomUtf8Bytes(BYTES_4K / 2);

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BATCH_OPERATOR),
                fileCreate("test").contents(old2k).lifetime(origLifetime).via(CREATE_TXN),
                atomicBatch(fileUpdate("test")
                                .contents(new4k)
                                .extendingExpiryBy(0)
                                .via("updateTo4")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(fileUpdate("test")
                                .contents(new2k)
                                .extendingExpiryBy(0)
                                .via("updateTo2")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(fileUpdate("test")
                                .extendingExpiryBy(extension)
                                .via("extend")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> {
                    final var createOp = getTxnRecord(CREATE_TXN);
                    final var to4kOp = getTxnRecord("updateTo4");
                    final var to2kOp = getTxnRecord("updateTo2");
                    final var extensionOp = getTxnRecord("extend");
                    allRunFor(spec, createOp, to4kOp, to2kOp, extensionOp);
                    final var createFee = createOp.getResponseRecord().getTransactionFee();
                    opLog.info("Creation : {} ", createFee);
                    opLog.info(
                            "New 4k   : {} ({})",
                            to4kOp.getResponseRecord().getTransactionFee(),
                            (to4kOp.getResponseRecord().getTransactionFee() - createFee));
                    opLog.info(
                            "New 2k   : {} ({})",
                            to2kOp.getResponseRecord().getTransactionFee(),
                            +(to2kOp.getResponseRecord().getTransactionFee() - createFee));
                    opLog.info(
                            "Extension: {} ({})",
                            extensionOp.getResponseRecord().getTransactionFee(),
                            (extensionOp.getResponseRecord().getTransactionFee() - createFee));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> vanillaUpdateSucceeds() {
        final byte[] old4K = randomUtf8Bytes(BYTES_4K);
        final byte[] new4k = randomUtf8Bytes(BYTES_4K);
        final String firstMemo = "Originally";
        final String secondMemo = "Subsequently";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BATCH_OPERATOR),
                fileCreate("test").entityMemo(firstMemo).contents(old4K),
                atomicBatch(fileUpdate("test")
                                .entityMemo(ZERO_BYTE_MEMO)
                                .contents(new4k)
                                .hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate("test")
                                .entityMemo(secondMemo)
                                .contents(new4k)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileContents("test").hasContents(ignore -> new4k),
                getFileInfo("test").hasMemo(secondMemo));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateImmutableFile() {
        final String file1 = "FILE_1";
        final String file2 = "FILE_2";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate(file1).contents("Hello World").unmodifiable(),
                fileCreate(file2).contents("Hello World").waclShape(SigControl.emptyList()),
                atomicBatch(fileUpdate(file1)
                                .contents("Goodbye World")
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(UNAUTHORIZED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate(file2)
                                .contents("Goodbye World")
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(UNAUTHORIZED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateExpirationPastMaxLifetime() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("test"),
                doWithStartupConfig("entities.maxLifetime", maxLifetime -> atomicBatch(fileUpdate("test")
                                .lifetime(parseLong(maxLifetime) + 12_345L)
                                .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)));
    }
}
