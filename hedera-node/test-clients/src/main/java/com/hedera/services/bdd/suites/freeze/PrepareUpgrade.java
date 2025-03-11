// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.initializeSettings;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHash;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileId;

import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public final class PrepareUpgrade extends HapiSuite {
    private static final Logger log = LogManager.getLogger(PrepareUpgrade.class);

    public static void main(String... args) {
        new PrepareUpgrade().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(prepareUpgrade());
    }

    final Stream<DynamicTest> prepareUpgrade() {
        return defaultHapiSpec("PrepareUpgrade")
                .given(initializeSettings())
                .when(sourcing(() -> UtilVerbs.prepareUpgrade()
                        .withUpdateFile(upgradeFileId())
                        .signedBy(GENESIS)
                        .payingWith(GENESIS)
                        .havingHash(upgradeFileHash())))
                .then();
    }
}
