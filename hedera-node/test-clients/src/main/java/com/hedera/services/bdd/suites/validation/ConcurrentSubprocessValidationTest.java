// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAllLogsAfter;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateStreams;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Validation test for concurrent subprocess tests that runs log validation followed by stream validation.
 * Uses @Isolated to ensure it runs in isolation and @Order(Integer.MAX_VALUE) to run last.
 *
 * <p>Log validation must run first because stream validation freezes the network.
 */
@Tag("CONCURRENT_SUBPROCESS_VALIDATION")
@Isolated
@Order(Integer.MAX_VALUE)
public class ConcurrentSubprocessValidationTest {
    private static final Duration VALIDATION_DELAY = Duration.ofSeconds(1);

    @LeakyHapiTest
    final Stream<DynamicTest> validateLogsAndStreams() {
        // Ensure we don't trigger any stake rebalancing that could interfere with validation
        HapiSpec.setStakerIds(null);
        return hapiTest(
                // First: validate logs (reads files, doesn't freeze network)
                validateAllLogsAfter(VALIDATION_DELAY),
                // Second: validate streams (freezes the network at the end)
                validateStreams());
    }
}
