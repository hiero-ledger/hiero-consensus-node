// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.initializeSettings;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileId;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFilePath;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class UpdateFileForUpgrade extends HapiSuite {
    private static final Logger log = LogManager.getLogger(UpdateFileForUpgrade.class);

    public static void main(String... args) {
        new UpdateFileForUpgrade().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(updateFileForUpgrade());
    }

    final Stream<DynamicTest> updateFileForUpgrade() {
        return defaultHapiSpec("UpdateFileForUpgrade")
                .given(initializeSettings())
                .when(sourcing(() -> {
                    try {
                        return UtilVerbs.updateSpecialFile(
                                GENESIS,
                                upgradeFileId(),
                                ByteString.copyFrom(Files.readAllBytes(Paths.get(upgradeFilePath()))),
                                TxnUtils.BYTES_4K,
                                upgradeFileAppendsPerBurst());
                    } catch (IOException e) {
                        e.printStackTrace();
                        return null;
                    }
                }))
                .then();
    }
}
