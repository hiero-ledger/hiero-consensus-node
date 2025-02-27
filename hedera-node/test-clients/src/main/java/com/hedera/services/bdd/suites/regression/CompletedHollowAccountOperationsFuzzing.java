// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountCompletedFuzzingFactory.hollowAccountFuzzingWith;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountCompletedFuzzingFactory.initOperations;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Fuzz test, testing different operations on completed hollow account
 */
@Tag(NOT_REPEATABLE)
public class CompletedHollowAccountOperationsFuzzing {
    private static final String PROPERTIES = "completed-hollow-account-fuzzing.properties";

    @HapiTest
    final Stream<DynamicTest> completedHollowAccountOperationsFuzzing() {
        return hapiTest(flattened(
                initOperations(),
                runWithProvider(hollowAccountFuzzingWith(PROPERTIES))
                        .maxOpsPerSec(10)
                        .loggingOff()
                        .lasting(10L, TimeUnit.SECONDS)));
    }
}
