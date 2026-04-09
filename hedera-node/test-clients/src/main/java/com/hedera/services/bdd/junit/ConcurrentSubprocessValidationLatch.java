// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A latch that gates execution of
 * {@link com.hedera.services.bdd.suites.validation.ConcurrentSubprocessValidationTest}
 * until all other test classes in the subprocess concurrent plan have finished.
 *
 * <p>Armed by {@link SharedNetworkLauncherSessionListener.SharedNetworkExecutionListener}
 * when it detects a subprocess concurrent test plan. Each non-validation test class that
 * finishes counts down the latch. The validation test's {@code @BeforeAll} awaits the latch
 * before proceeding, ensuring it runs after all other tests without holding any JUnit
 * resource locks.
 */
public final class ConcurrentSubprocessValidationLatch {
    private static final Logger log = LogManager.getLogger(ConcurrentSubprocessValidationLatch.class);
    private static final AtomicReference<CountDownLatch> LATCH = new AtomicReference<>();

    private ConcurrentSubprocessValidationLatch() {}

    /**
     * Arms the latch with the number of non-validation test classes.
     *
     * @param nonValidationClassCount number of test classes to wait for
     */
    public static void arm(final int nonValidationClassCount) {
        log.debug("Arming validation latch for {} non-validation test classes", nonValidationClassCount);
        LATCH.set(new CountDownLatch(nonValidationClassCount));
    }

    /**
     * Counts down one class completion. Called from the test execution listener
     * when a non-validation test class finishes.
     */
    public static void countDown() {
        final var latch = LATCH.get();
        if (latch != null) {
            latch.countDown();
            log.debug("Validation latch count now: {}", latch.getCount());
        }
    }

    /**
     * Awaits all non-validation test classes to finish. Uses {@link ForkJoinPool.ManagedBlocker}
     * to allow the ForkJoinPool to compensate for the blocked thread.
     *
     * <p>If the latch was never armed (e.g., not running in subprocess concurrent mode),
     * returns immediately.
     */
    public static void awaitAllDone() {
        final var latch = LATCH.get();
        if (latch == null) {
            return;
        }
        if (latch.getCount() == 0) {
            log.debug("All non-validation tests already finished, proceeding with validation");
            return;
        }
        log.debug("Waiting for {} non-validation test classes to finish before validation", latch.getCount());
        try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override
                public boolean block() throws InterruptedException {
                    latch.await();
                    return true;
                }

                @Override
                public boolean isReleasable() {
                    return latch.getCount() == 0;
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for non-validation tests to finish", e);
        }
        log.debug("All non-validation tests finished, proceeding with validation");
    }
}
