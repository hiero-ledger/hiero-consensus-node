// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.initializeSettings;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeDelay;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHash;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileId;

import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public final class FreezeUpgrade extends HapiSuite {
    private static final Logger log = LogManager.getLogger(FreezeUpgrade.class);

    public static void main(String... args) {
        new FreezeUpgrade().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(freezeUpgrade());
    }

    final Stream<DynamicTest> freezeUpgrade() {
        return defaultHapiSpec("FreezeUpgrade")
                .given(initializeSettings())
                .when(sourcing(() -> UtilVerbs.freezeUpgrade()
                        .startingIn(upgradeDelay())
                        .minutes()
                        .withUpdateFile(upgradeFileId())
                        .payingWith(GENESIS)
                        .havingHash(upgradeFileHash())))
                .then();
    }
}
