// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_DETAILS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of UpdateFailuresSpec. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
class AtomicUpdateFailuresSpec {

    private static final long A_LOT = 1_234_567_890L;
    private static final String CIVILIAN = "civilian";
    private static final String BATCH_OPERATOR = "batchOperator";

    @HapiTest
    final Stream<DynamicTest> confusedUpdateCantExtendExpiry() {
        // this test verify that the exchange rate file parsed correctly on update, it doesn't check expiry
        var initialExpiry = new AtomicLong();
        var extension = 1_000L;
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                withOpContext((spec, opLog) -> {
                    var infoOp = QueryVerbs.getFileInfo(EXCHANGE_RATES);
                    CustomSpecAssert.allRunFor(spec, infoOp);
                    var info = infoOp.getResponse().getFileGetInfo().getFileInfo();
                    initialExpiry.set(info.getExpirationTime().getSeconds());
                }),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .contents("NONSENSE".getBytes())
                                .extendingExpiryBy(extension)
                                .hasKnownStatus(ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                QueryVerbs.getFileInfo(EXCHANGE_RATES).hasExpiry(initialExpiry::get));
    }

    @HapiTest
    final Stream<DynamicTest> precheckRejectsUnauthorized() {
        // this test is to verify that the system files cannot be updated without privileged account
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(CIVILIAN),
                atomicBatch(fileUpdate(ADDRESS_BOOK)
                                .payingWith(CIVILIAN)
                                .hasKnownStatus(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate(NODE_DETAILS)
                                .payingWith(CIVILIAN)
                                .hasKnownStatus(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate(API_PERMISSIONS)
                                .payingWith(CIVILIAN)
                                .hasKnownStatus(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate(APP_PROPERTIES)
                                .payingWith(CIVILIAN)
                                .hasKnownStatus(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate(FEE_SCHEDULE)
                                .payingWith(CIVILIAN)
                                .hasKnownStatus(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .payingWith(CIVILIAN)
                                .hasKnownStatus(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> precheckAllowsMissing() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                atomicBatch(fileUpdate("1.2.3")
                                .payingWith(GENESIS)
                                .signedBy(GENESIS)
                                .fee(1_234_567L)
                                .hasPrecheck(OK)
                                .hasKnownStatus(INVALID_FILE_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> precheckAllowsDeleted() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("tbd"),
                fileDelete("tbd"),
                atomicBatch(fileUpdate("tbd")
                                .hasPrecheck(OK)
                                .hasKnownStatus(FILE_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> precheckRejectsPrematureExpiry() {
        long now = Instant.now().getEpochSecond();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("file"),
                atomicBatch(fileUpdate("file")
                                .fee(A_LOT)
                                .extendingExpiryBy(-now)
                                .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> precheckAllowsBadEncoding() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("file"),
                atomicBatch(fileUpdate("file")
                                .fee(A_LOT)
                                .signedBy(GENESIS)
                                .useBadWacl()
                                .hasPrecheck(OK)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> handleIgnoresEarlierExpiry() {
        var initialExpiry = new AtomicLong();

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("file"),
                withOpContext((spec, opLog) ->
                        initialExpiry.set(spec.registry().getTimestamp("file").getSeconds())),
                atomicBatch(fileUpdate("file").extendingExpiryBy(-1_000).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                UtilVerbs.assertionsHold((spec, opLog) -> {
                    var infoOp = QueryVerbs.getFileInfo("file");
                    CustomSpecAssert.allRunFor(spec, infoOp);
                    var currExpiry = infoOp.getResponse()
                            .getFileGetInfo()
                            .getFileInfo()
                            .getExpirationTime()
                            .getSeconds();
                    assertEquals(initialExpiry.get(), currExpiry, "Expiry changed unexpectedly!");
                }));
    }
}
