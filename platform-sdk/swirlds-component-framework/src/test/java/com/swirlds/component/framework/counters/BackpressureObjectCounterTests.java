// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackpressureObjectCounterTests {

    /**
     * Choose a capacity that is sufficiently high as to never trigger. Validate that the counting part of this
     * implementation works as expected.
     */
    @Test
    void countWithHighCapacityTest() {
        final Random random = getRandomPrintSeed();

        final ObjectCounter counter = new BackpressureObjectCounter("test", 1_000_000_000, Duration.ofMillis(1));

        int count = 0;
        for (int i = 0; i < 1000; i++) {

            final boolean increment = count == 0 || random.nextBoolean();

            if (increment) {
                count++;

                // All of these methods are logically equivalent with current capacity.
                final int choice = random.nextInt(3);
                switch (choice) {
                    case 0 -> counter.onRamp();
                    case 1 -> counter.attemptOnRamp();
                    case 2 -> counter.forceOnRamp();
                    default -> throw new IllegalStateException("Unexpected value: " + choice);
                }

            } else {
                count--;
                counter.offRamp();
            }

            assertEquals(count, counter.getCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void onRampTest(final int sleepMillis) throws InterruptedException {
        final Duration sleepDuration = Duration.ofMillis(sleepMillis);

        final ObjectCounter counter = new BackpressureObjectCounter("test", 10, sleepDuration);

        // Fill up the counter to capacity
        for (int i = 0; i < 10; i++) {
            counter.onRamp();
        }

        assertEquals(10, counter.getCount());

        // Attempt to add one more, should block.
        final AtomicBoolean added = new AtomicBoolean(false);
        final AtomicReference<Boolean> interrupted = new AtomicReference<>();
        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    counter.onRamp();
                    added.set(true);

                    interrupted.set(Thread.currentThread().isInterrupted());
                })
                .build(true);

        assertEquals(10, counter.getCount());

        // Sleep for a little while. Thread should be unable to on ramp another element.
        // Count can briefly overflow to 11, but should quickly return to 10.
        MILLISECONDS.sleep(50);
        final long count1 = counter.getCount();
        assertTrue(count1 == 10 || count1 == 11, "unexpected count " + count1);

        // Interrupting the thread should not unblock us.
        thread.interrupt();
        MILLISECONDS.sleep(50);
        // Count can briefly overflow to 11, but should quickly return to 10.
        final long count2 = counter.getCount();
        assertTrue(count2 == 10 || count2 == 11, "unexpected count " + count2);

        // Off ramp one element. Thread should become unblocked.
        counter.offRamp();

        assertEventuallyTrue(added::get, Duration.ofSeconds(10), "Thread should have been unblocked");

        // even though the interrupt did not unblock the thread, the interrupt should not have been squelched.
        assertEventuallyEquals(true, interrupted::get, Duration.ofSeconds(10), "Thread should have been interrupted");

        assertEquals(10, counter.getCount());
    }

    @Test
    void attemptOnRampTest() {
        final ObjectCounter counter = new BackpressureObjectCounter("test", 10, Duration.ofMillis(1));

        // Fill up the counter to capacity
        for (int i = 0; i < 10; i++) {
            assertTrue(counter.attemptOnRamp());
        }

        assertEquals(10, counter.getCount());

        // Attempt to add one more, should block immediately fail and return false.
        assertFalse(counter.attemptOnRamp());

        assertEquals(10, counter.getCount());
    }

    @Test
    void forceOnRampTest() {
        final ObjectCounter counter = new BackpressureObjectCounter("test", 10, Duration.ofMillis(1));

        // Fill up the counter to capacity
        for (int i = 0; i < 10; i++) {
            counter.forceOnRamp();
        }

        assertEquals(10, counter.getCount());

        // Attempt to add one more, should work even though it violates capacity restrictions
        counter.forceOnRamp();

        assertEquals(11, counter.getCount());
    }

    @Test
    void waitUntilEmptyTest() throws InterruptedException {
        final ObjectCounter counter = new BackpressureObjectCounter("test", 1000, Duration.ofMillis(1));

        for (int i = 0; i < 100; i++) {
            counter.onRamp();
        }

        final AtomicBoolean empty = new AtomicBoolean(false);
        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    counter.waitUntilEmpty();
                    empty.set(true);
                })
                .build(true);

        // Should be blocked.
        MILLISECONDS.sleep(50);
        assertFalse(empty.get());

        // Draining most of the things from the counter should still block.
        for (int i = 0; i < 90; i++) {
            counter.offRamp();
        }
        MILLISECONDS.sleep(50);
        assertFalse(empty.get());

        // Interrupting the thread should have no effect.
        thread.interrupt();
        MILLISECONDS.sleep(50);
        assertFalse(empty.get());

        // Removing remaining things from the counter should unblock.
        for (int i = 0; i < 10; i++) {
            counter.offRamp();
        }

        assertEventuallyTrue(empty::get, Duration.ofSeconds(10), "Counter did not empty in time.");
    }

    /**
     * If the fork join pool runs out of threads, back pressure should handle the situation gracefully.
     */
    @Test
    void backpressureDoesntOverwhelmForkJoinPool() throws InterruptedException {

        final int maxPoolSize = 10;
        final ForkJoinPool pool = new ForkJoinPool(
                5,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                false,
                0,
                maxPoolSize,
                1,
                null,
                60,
                TimeUnit.SECONDS);

        final AtomicBoolean blocked = new AtomicBoolean(true);
        final ManagedBlocker dummyBlocker = new ManagedBlocker() {
            @Override
            public boolean block() throws InterruptedException {
                MILLISECONDS.sleep(1);
                return false;
            }

            @Override
            public boolean isReleasable() {
                return !blocked.get();
            }
        };

        // Keep submitting managed blockers until the fork join pool taps out.
        final AtomicBoolean poolIsSaturated = new AtomicBoolean(false);
        for (int i = 0; i < maxPoolSize; i++) {
            pool.submit(() -> {
                int tries = 1000;
                while (tries-- > 0) {
                    try {
                        ForkJoinPool.managedBlock(dummyBlocker);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (final RejectedExecutionException ex) {
                        poolIsSaturated.set(true);
                    }
                }
            });
        }

        assertEventuallyTrue(poolIsSaturated::get, Duration.ofSeconds(10), "Fork join pool did not saturate in time.");

        // Now, see if a backpressure counter can block without throwing.

        final ObjectCounter counter = new BackpressureObjectCounter("test", 1, Duration.ofMillis(1));
        counter.onRamp();

        final AtomicBoolean taskCompleted = new AtomicBoolean(false);
        final AtomicBoolean exceptionThrown = new AtomicBoolean(false);
        pool.submit(() -> {

            // This should block until we off ramp.
            try {
                counter.onRamp();
            } catch (final Throwable t) {
                exceptionThrown.set(true);
                return;
            }

            taskCompleted.set(true);
        });

        // Sleep for a while, the task should not be able to complete.
        MILLISECONDS.sleep(50);
        assertFalse(taskCompleted.get());
        assertFalse(exceptionThrown.get());

        // Unblock the counter, the task should complete.
        counter.offRamp();
        assertEventuallyTrue(taskCompleted::get, Duration.ofSeconds(10), "Task did not complete in time.");
        assertFalse(exceptionThrown.get());

        pool.shutdown();
    }
}
