// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.validation;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAllLogsAfter;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateStreams;

import com.hedera.services.bdd.junit.ConcurrentSubprocessValidationLatch;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Validation test for concurrent subprocess tests that runs log validation followed by stream
 * validation. Uses {@code @Order(Integer.MAX_VALUE)} as a scheduling hint to run last.
 *
 * <p>In subprocess concurrent mode (classes run in parallel), JUnit may pick this class before
 * all others have finished. The {@code @BeforeAll} method blocks on a latch that is armed by
 * {@link com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.SharedNetworkExecutionListener}
 * and counted down as each non-validation test class completes. Crucially, this blocking happens
 * BEFORE JUnit acquires the method-level {@code READ_WRITE} resource lock from
 * {@code @LeakyHapiTest}, so no locks are held while waiting and other tests run freely.
 *
 * <p>Log validation must run first because stream validation freezes the network.
 */
@Tag("CONCURRENT_SUBPROCESS_VALIDATION")
public class ConcurrentSubprocessValidationTest {
    private static final Duration VALIDATION_DELAY = Duration.ofSeconds(1);

    /**
     * Blocks until all other test classes in the plan have finished. Runs before JUnit
     * acquires the method-level READ_WRITE lock, so no resource contention while waiting.
     */
    @BeforeAll
    static void awaitOtherTests() {
        ConcurrentSubprocessValidationLatch.awaitAllDone();
    }

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
