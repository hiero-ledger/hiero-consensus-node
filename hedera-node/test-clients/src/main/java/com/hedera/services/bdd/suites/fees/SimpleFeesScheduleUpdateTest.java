// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;

import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
public class SimpleFeesScheduleUpdateTest {

    private static final String SIMPLE_FEES_SNAPSHOT = "simpleFeesSnapshot";

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> simpleFeesScheduleControlAccountIsntCharged() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, 1_000_000_000_000L)),
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry(SIMPLE_FEES_SNAPSHOT),
                balanceSnapshot("pre", FEE_SCHEDULE_CONTROL),
                updateLargeFile(FEE_SCHEDULE_CONTROL, SIMPLE_FEE_SCHEDULE, SIMPLE_FEES_SNAPSHOT),
                getAccountBalance(FEE_SCHEDULE_CONTROL).hasTinyBars(changeFromSnapshot("pre", 0)));
    }

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> canRetrieveSimpleFeesScheduleContents() {
        return hapiTest(getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
            if (bytes.length == 0) {
                throw new AssertionError("Simple fee schedule should not be empty");
            }
        }));
    }

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> invalidSimpleFeesScheduleIsRejected() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, 1_000_000_000_000L)),
                fileUpdate(SIMPLE_FEE_SCHEDULE)
                        .contents("INVALID_GARBAGE_BYTES")
                        .payingWith(FEE_SCHEDULE_CONTROL)
                        .hasKnownStatus(FEE_SCHEDULE_FILE_PART_UPLOADED));
    }

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> simpleFeesScheduleSupportsFileAppend() {
        return hapiTest(
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry(SIMPLE_FEES_SNAPSHOT),
                updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, SIMPLE_FEES_SNAPSHOT));
    }
}
