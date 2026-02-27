// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that file 0.0.113 (simpleFeeSchedule) survives a freeze upgrade and remains
 * queryable and updatable afterward. This is a regression test for the case where
 * upgrading from a pre-simple-fees version (e.g. v0.71.1) left the file entity missing
 * in state, causing FileUpdate to fail with INVALID_FILE_ID.
 */
@Tag(UPGRADE)
@HapiTestLifecycle
@OrderedInIsolation
public class SimpleFeeScheduleUpgradeTest implements LifecycleTest {

    @HapiTest
    final Stream<DynamicTest> simpleFeeScheduleSurvivesUpgradeAndIsUpdatable() {
        return hapiTest(
                // Verify the file exists and has content before upgrade
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
                    assertTrue(bytes.length > 0, "Simple fee schedule should have content before upgrade");
                }),
                // Perform a fake upgrade
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(),
                // Verify the file is still queryable after upgrade
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
                    assertTrue(bytes.length > 0, "Simple fee schedule should have content after upgrade");
                }),
                // Verify the file is updatable after upgrade
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, 1_000_000_000_000L)),
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry("originalSimpleFees"),
                updateLargeFile(FEE_SCHEDULE_CONTROL, SIMPLE_FEE_SCHEDULE, "originalSimpleFees"),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
                    assertTrue(bytes.length > 0, "Simple fee schedule should have content after update");
                }));
    }
}
