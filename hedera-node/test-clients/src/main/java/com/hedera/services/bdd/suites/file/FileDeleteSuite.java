// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class FileDeleteSuite {
    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                fileCreate("file").contents("ABC"),
                submitModified(withSuccessivelyVariedBodyIds(), () -> fileDelete("file")));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> canDeleteWithAnyOneOfTopLevelKeyList() {
        KeyShape shape = listOf(SIMPLE, threshOf(1, 2), listOf(2));
        SigControl deleteSigs = shape.signedWith(sigs(ON, sigs(OFF, OFF), sigs(ON, OFF)));

        return hapiTest(fileCreate("test").waclShape(shape), fileDelete("test").sigControl(forKey("test", deleteSigs)));
    }

    @HapiTest
    final Stream<DynamicTest> getDeletedFileInfo() {
        return hapiTest(
                fileCreate("deletedFile").logged(),
                fileDelete("deletedFile").logged(),
                getFileInfo("deletedFile").hasAnswerOnlyPrecheck(OK).hasDeleted(true));
    }

    @HapiTest
    final Stream<DynamicTest> handleRejectsMissingFile() {
        return hapiTest(fileDelete("1.2.3").signedBy(GENESIS).hasKnownStatus(ResponseCodeEnum.INVALID_FILE_ID));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> handleRejectsDeletedFile() {
        return hapiTest(
                fileCreate("tbd"), fileDelete("tbd"), fileDelete("tbd").hasKnownStatus(ResponseCodeEnum.FILE_DELETED));
    }
}
