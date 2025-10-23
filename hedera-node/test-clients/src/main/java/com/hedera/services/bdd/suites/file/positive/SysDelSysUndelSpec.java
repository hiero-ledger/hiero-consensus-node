// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.positive;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileUndelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModifiedWithFixedPayer;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_UNDELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class SysDelSysUndelSpec {
    byte[] ORIG_FILE = "SOMETHING".getBytes();

    @HapiTest
    final Stream<DynamicTest> sysDelIdVariantsTreatedAsExpected() {
        return hapiTest(
                fileCreate("misc").contents(ORIG_FILE),
                submitModifiedWithFixedPayer(withSuccessivelyVariedBodyIds(), () -> systemFileDelete("misc")
                        .payingWith(SYSTEM_DELETE_ADMIN)));
    }

    @HapiTest
    final Stream<DynamicTest> sysUndelIdVariantsTreatedAsExpected() {
        return hapiTest(
                fileCreate("misc").contents(ORIG_FILE),
                systemFileDelete("misc").payingWith(SYSTEM_DELETE_ADMIN),
                submitModifiedWithFixedPayer(withSuccessivelyVariedBodyIds(), () -> systemFileUndelete("misc")
                        .payingWith(SYSTEM_UNDELETE_ADMIN)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> distinguishesAdminPrivileges() {
        final var lifetime = THREE_MONTHS_IN_SECONDS;

        return hapiTest(
                fileCreate("misc").lifetime(lifetime).contents(ORIG_FILE),
                systemFileDelete("misc").payingWith(SYSTEM_UNDELETE_ADMIN).hasPrecheck(NOT_SUPPORTED),
                systemFileUndelete("misc").payingWith(SYSTEM_DELETE_ADMIN).hasPrecheck(AUTHORIZATION_FAILED),
                systemFileDelete(ADDRESS_BOOK).payingWith(GENESIS).hasPrecheck(ENTITY_NOT_ALLOWED_TO_DELETE));
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteWithPastExpiryDestroysFile() {
        final var lifetime = THREE_MONTHS_IN_SECONDS;

        return hapiTest(
                fileCreate("misc").lifetime(lifetime).contents(ORIG_FILE),
                systemFileDelete("misc").payingWith(SYSTEM_DELETE_ADMIN).updatingExpiry(1L),
                getFileInfo("misc").nodePayment(1_234L).hasAnswerOnlyPrecheck(INVALID_FILE_ID),
                systemFileUndelete("misc").payingWith(SYSTEM_UNDELETE_ADMIN).hasKnownStatus(INVALID_FILE_ID));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> systemDeleteThenUndeleteRestoresContentsAndExpiry() {
        var now = Instant.now().getEpochSecond();
        var lifetime = THREE_MONTHS_IN_SECONDS;
        AtomicLong initExpiry = new AtomicLong();

        return hapiTest(
                fileCreate("misc").lifetime(lifetime).contents(ORIG_FILE),
                UtilVerbs.withOpContext((spec, opLog) ->
                        initExpiry.set(spec.registry().getTimestamp("misc").getSeconds())),
                systemFileDelete("misc").payingWith(SYSTEM_DELETE_ADMIN).fee(0L).updatingExpiry(now + lifetime - 1_000),
                getFileInfo("misc")
                        .nodePayment(1_234L)
                        .hasAnswerOnlyPrecheck(OK)
                        .hasDeleted(true),
                systemFileUndelete("misc").payingWith(SYSTEM_UNDELETE_ADMIN).fee(0L),
                getFileContents("misc").hasContents(ignore -> ORIG_FILE),
                getFileInfo("misc").hasExpiry(initExpiry::get));
    }
}
