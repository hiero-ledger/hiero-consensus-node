// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.BYTES_4K;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeAbort;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_UPGRADE_HAS_BEEN_PREPARED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Exercises the prerequisites of a {@code FREEZE_UPGRADE}. The upgrade file {@code 0.0.150} is staged so
 * that the pre-handle file-hash check passes, but {@code PREPARE_UPGRADE} is deliberately skipped, so the
 * freeze store holds no prepared upgrade hash.
 *
 * <p>A {@code FREEZE_UPGRADE} is expected to be honored only when it confirms a prepared upgrade, so the
 * network-admin service design requires this transaction to be rejected with
 * {@code NO_UPGRADE_HAS_BEEN_PREPARED}. Because it is rejected, no freeze is scheduled and no
 * {@code FREEZE_ABORT} is needed.
 */
@Tag(UPGRADE)
@HapiTestLifecycle
public class FreezeUpgradePrerequisitesTest implements LifecycleTest {
    @HapiTest
    final Stream<DynamicTest> freezeUpgradeIsRejectedWithoutPreparedUpgrade() {
        return hapiTest(
                // Clear any upgrade prepared by an earlier spec sharing this network, so the condition
                // under test - that no upgrade has been prepared - holds regardless of execution order.
                freezeAbort().payingWith(GENESIS),
                buildUpgradeZipFrom(FAKE_ASSETS_LOC),
                // Stage 0.0.150 so the pre-handle file-hash check passes, without a PREPARE_UPGRADE.
                sourcing(() -> updateSpecialFile(
                        GENESIS,
                        DEFAULT_UPGRADE_FILE_ID,
                        FAKE_UPGRADE_ZIP_LOC,
                        BYTES_4K,
                        upgradeFileAppendsPerBurst())),
                sourcing(() -> freezeUpgrade()
                        .startingIn(60)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))
                        .payingWith(GENESIS)
                        .hasKnownStatus(NO_UPGRADE_HAS_BEEN_PREPARED)));
    }
}
