// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.regression.factories.AccountCompletionFuzzingFactory.hollowAccountFuzzingWith;
import static com.hedera.services.bdd.suites.regression.factories.AccountCompletionFuzzingFactory.initOperations;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
public class HollowAccountCompletionFuzzing {
    private static final String PROPERTIES = "hollow-account-completion-fuzzing.properties";

    @HapiTest
    final Stream<DynamicTest> hollowAccountCompletionFuzzing() {
        return hapiTest(flattened(
                initOperations(),
                runWithProvider(hollowAccountFuzzingWith(PROPERTIES))
                        .maxOpsPerSec(10)
                        .lasting(10L, TimeUnit.SECONDS)));
    }
}
