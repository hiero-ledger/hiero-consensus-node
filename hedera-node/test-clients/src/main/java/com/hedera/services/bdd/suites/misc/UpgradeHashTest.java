// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeAbort;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.purgeUpgradeArtifacts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.telemetryUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.FREEZE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
class UpgradeHashTest {

    private static final byte[] NONEMPTY_MISMATCHING_HASH = new byte[48];

    static {
        NONEMPTY_MISMATCHING_HASH[0] = 1;
    }

    @HapiTest
    final Stream<DynamicTest> mismatchedHashPreventsSuccessfulUpgrades() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FREEZE_ADMIN, 1000 * ONE_HBAR)),
                buildUpgradeZipFrom(FAKE_ASSETS_LOC),
                // sourcing() so the file isn't eagerly read
                sourcing(() -> updateSpecialFile(
                        GENESIS,
                        DEFAULT_UPGRADE_FILE_ID,
                        FAKE_UPGRADE_ZIP_LOC,
                        TxnUtils.BYTES_4K,
                        upgradeFileAppendsPerBurst())),
                purgeUpgradeArtifacts(),
                // Verify all upgrade types check the file hash
                prepareUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(NONEMPTY_MISMATCHING_HASH)
                        .startingIn(5)
                        .minutes()
                        .hasKnownStatus(UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED),
                freezeUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(NONEMPTY_MISMATCHING_HASH)
                        .startingIn(5)
                        .minutes()
                        .hasKnownStatus(UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED),
                telemetryUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(NONEMPTY_MISMATCHING_HASH)
                        .startingIn(5)
                        .minutes()
                        .hasKnownStatus(UPDATE_FILE_HASH_DOES_NOT_MATCH_PREPARED),
                // Verify that a transaction with the correct file hash is successful
                sourcing(() -> prepareUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))
                        .startingIn(5)
                        .minutes()),
                // Abort the freeze to restore the network to a non-freeze state
                freezeAbort());
    }
}
