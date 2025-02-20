// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;

import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public final class FreezeAbort extends HapiSuite {
    private static final Logger log = LogManager.getLogger(FreezeAbort.class);

    public static void main(String... args) {
        new FreezeAbort().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(freezeAbort());
    }

    final Stream<DynamicTest> freezeAbort() {
        return defaultHapiSpec("FreezeAbort")
                .given()
                .when(UtilVerbs.freezeAbort().payingWith(GENESIS))
                .then();
    }
}
