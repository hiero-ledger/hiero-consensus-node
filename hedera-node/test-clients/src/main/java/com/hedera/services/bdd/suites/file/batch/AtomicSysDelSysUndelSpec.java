// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileUndelete;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_UNDELETE_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// SystemDelete and SystemUndelete are not in the atomicBatch.whitelist, so even their admins can't batch
// them; SysDelSysUndelSpec covers both outside of a batch
@Tag(ATOMIC_BATCH)
@OrderedInIsolation
class AtomicSysDelSysUndelSpec {

    private static final byte[] ORIG_FILE = "SOMETHING".getBytes();
    private static final String BATCH_OPERATOR = "batchOperator";

    @HapiTest
    final Stream<DynamicTest> systemDeleteIsNotWhitelisted() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").contents(ORIG_FILE),
                atomicBatch(systemFileDelete("misc")
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                getFileInfo("misc").hasDeleted(false),
                getFileContents("misc").hasContents(ignore -> ORIG_FILE));
    }

    @HapiTest
    final Stream<DynamicTest> systemUndeleteIsNotWhitelisted() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").contents(ORIG_FILE),
                systemFileDelete("misc").payingWith(SYSTEM_DELETE_ADMIN),
                atomicBatch(systemFileUndelete("misc")
                                .payingWith(SYSTEM_UNDELETE_ADMIN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                getFileInfo("misc")
                        .nodePayment(1_234L)
                        .hasAnswerOnlyPrecheck(OK)
                        .hasDeleted(true));
    }

    @HapiTest
    final Stream<DynamicTest> superuserCannotBatchSystemDelete() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate("misc").contents(ORIG_FILE),
                atomicBatch(systemFileDelete("misc").payingWith(GENESIS).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                getFileInfo("misc").hasDeleted(false));
    }
}
