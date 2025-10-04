// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of FileDeleteSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
class AtomicFileDeleteSuite {

    private static final String BATCH_OPERATOR = "batchOperator";

    @HapiTest
    final Stream<DynamicTest> canDeleteWithAnyOneOfTopLevelKeyList() {
        KeyShape shape = listOf(SIMPLE, threshOf(1, 2), listOf(2));
        SigControl deleteSigs = shape.signedWith(sigs(ON, sigs(OFF, OFF), sigs(ON, OFF)));

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                fileCreate("test").waclShape(shape),
                atomicBatch(fileDelete("test")
                                .sigControl(forKey("test", deleteSigs))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> getDeletedFileInfo() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                fileCreate("deletedFile").logged(),
                atomicBatch(fileDelete("deletedFile").logged().batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileInfo("deletedFile").hasAnswerOnlyPrecheck(OK).hasDeleted(true));
    }

    @HapiTest
    final Stream<DynamicTest> handleRejectsMissingFile() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(fileDelete("1.2.3")
                                .signedBy(GENESIS)
                                .hasKnownStatus(ResponseCodeEnum.INVALID_FILE_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> handleRejectsDeletedFile() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                fileCreate("tbd"),
                fileDelete("tbd"),
                atomicBatch(fileDelete("tbd")
                                .hasKnownStatus(ResponseCodeEnum.FILE_DELETED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
