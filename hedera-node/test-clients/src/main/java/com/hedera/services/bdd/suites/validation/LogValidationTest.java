// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAllLogsAfter;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag("LOG_VALIDATION")
// Ordered to come after any other HapiTest that runs in a PR check
@Order(Integer.MAX_VALUE - 1)
public class LogValidationTest {
    private static final Duration VALIDATION_DELAY = Duration.ofSeconds(1);

    @LeakyHapiTest
    final Stream<DynamicTest> logsContainNoUnexpectedProblems() {
        return hapiTest(validateAllLogsAfter(VALIDATION_DELAY));
    }
}
