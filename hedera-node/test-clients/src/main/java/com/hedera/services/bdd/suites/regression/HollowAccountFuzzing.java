// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountFuzzingFactory.hollowAccountFuzzingTest;
import static com.hedera.services.bdd.suites.regression.factories.HollowAccountFuzzingFactory.initOperations;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(NOT_REPEATABLE)
public class HollowAccountFuzzing {
    private static final String PROPERTIES = "hollow-account-fuzzing.properties";

    @HapiTest
    final Stream<DynamicTest> hollowAccountFuzzing() {
        return hapiTest(flattened(
                initOperations(),
                runWithProvider(hollowAccountFuzzingTest(PROPERTIES))
                        .maxOpsPerSec(10)
                        .lasting(10L, TimeUnit.SECONDS)));
    }
}
