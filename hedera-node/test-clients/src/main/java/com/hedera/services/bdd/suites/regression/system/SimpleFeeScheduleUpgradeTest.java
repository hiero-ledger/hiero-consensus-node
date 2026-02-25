// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
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
    private static final AtomicReference<byte[]> PRE_UPGRADE_CONTENTS = new AtomicReference<>();

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> simpleFeeScheduleExistsBeforeUpgrade() {
        return hapiTest(getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
            assertTrue(bytes.length > 0, "Simple fee schedule should have content before upgrade");
            PRE_UPGRADE_CONTENTS.set(bytes);
        }));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> upgradePreservesSimpleFeeSchedule() {
        return hapiTest(prepareFakeUpgrade(), upgradeToNextConfigVersion());
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> simpleFeeScheduleQueryableAfterUpgrade() {
        return hapiTest(getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
            assertTrue(bytes.length > 0, "Simple fee schedule should have content after upgrade");
        }));
    }

    @HapiTest
    @Order(3)
    final Stream<DynamicTest> simpleFeeScheduleUpdatableAfterUpgrade() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, 1_000_000_000_000L)),
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry("originalSimpleFees"),
                updateLargeFile(FEE_SCHEDULE_CONTROL, SIMPLE_FEE_SCHEDULE, "originalSimpleFees"),
                // Verify the file can still be read after update
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(bytes -> {
                    assertTrue(bytes.length > 0, "Simple fee schedule should have content after update");
                }));
    }
}
