// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileUndelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModifiedWithFixedPayer;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_UNDELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of SysDelSysUndelSpec. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicSysDelSysUndelSpec {

    byte[] ORIG_FILE = "SOMETHING".getBytes();
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> sysDelIdVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").contents(ORIG_FILE),
                submitModifiedWithFixedPayer(withSuccessivelyVariedBodyIds(), () -> atomicBatch(systemFileDelete("misc")
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)));
    }

    @HapiTest
    final Stream<DynamicTest> sysUndelIdVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").contents(ORIG_FILE),
                systemFileDelete("misc").payingWith(SYSTEM_DELETE_ADMIN),
                submitModifiedWithFixedPayer(
                        withSuccessivelyVariedBodyIds(), () -> atomicBatch(systemFileUndelete("misc")
                                        .payingWith(SYSTEM_UNDELETE_ADMIN)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)));
    }

    @HapiTest
    final Stream<DynamicTest> distinguishesAdminPrivileges() {
        final var lifetime = THREE_MONTHS_IN_SECONDS;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").lifetime(lifetime).contents(ORIG_FILE),
                atomicBatch(systemFileDelete("misc")
                                .payingWith(SYSTEM_UNDELETE_ADMIN)
                                .hasPrecheck(NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(systemFileUndelete("misc")
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .hasPrecheck(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(systemFileDelete(ADDRESS_BOOK)
                                .payingWith(GENESIS)
                                .hasPrecheck(ENTITY_NOT_ALLOWED_TO_DELETE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteWithPastExpiryDestroysFile() {
        final var lifetime = THREE_MONTHS_IN_SECONDS;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").lifetime(lifetime).contents(ORIG_FILE),
                atomicBatch(systemFileDelete("misc")
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .updatingExpiry(1L)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileInfo("misc").nodePayment(1_234L).hasAnswerOnlyPrecheck(INVALID_FILE_ID),
                atomicBatch(systemFileUndelete("misc")
                                .payingWith(SYSTEM_UNDELETE_ADMIN)
                                .hasKnownStatus(INVALID_FILE_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteThenUndeleteRestoresContentsAndExpiry() {
        var now = Instant.now().getEpochSecond();
        var lifetime = THREE_MONTHS_IN_SECONDS;
        AtomicLong initExpiry = new AtomicLong();

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").lifetime(lifetime).contents(ORIG_FILE),
                UtilVerbs.withOpContext((spec, opLog) ->
                        initExpiry.set(spec.registry().getTimestamp("misc").getSeconds())),
                atomicBatch(systemFileDelete("misc")
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .fee(0L)
                                .updatingExpiry(now + lifetime - 1_000)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileInfo("misc")
                        .nodePayment(1_234L)
                        .hasAnswerOnlyPrecheck(OK)
                        .hasDeleted(true),
                atomicBatch(systemFileUndelete("misc")
                                .payingWith(SYSTEM_UNDELETE_ADMIN)
                                .fee(0L)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileContents("misc").hasContents(ignore -> ORIG_FILE),
                getFileInfo("misc").hasExpiry(initExpiry::get));
    }
}
