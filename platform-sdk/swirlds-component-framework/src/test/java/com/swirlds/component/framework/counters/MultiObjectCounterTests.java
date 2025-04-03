// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MultiObjectCounterTests {

    @Test
    void onRampOffRampTest() {
        final Random random = getRandomPrintSeed();

        final ObjectCounter counterA = new StandardObjectCounter(Duration.ofSeconds(1));
        final ObjectCounter counterB = new StandardObjectCounter(Duration.ofSeconds(1));
        final ObjectCounter counterC = new StandardObjectCounter(Duration.ofSeconds(1));

        final MultiObjectCounter counter = new MultiObjectCounter(counterA, counterB, counterC);

        int expectedCount = 0;
        for (int i = 0; i < 1000; i++) {

            if (expectedCount == 0 || random.nextDouble() < 0.75) {
                counter.onRamp();
                expectedCount++;
            } else {
                counter.offRamp();
                expectedCount--;
            }

            assertEquals(expectedCount, counter.getCount());
            assertEquals(expectedCount, counterA.getCount());
            assertEquals(expectedCount, counterB.getCount());
            assertEquals(expectedCount, counterC.getCount());
        }
    }

    @Test
    void attemptOnRampTest() {
        final Random random = getRandomPrintSeed();

        // When attempting an on ramp, only the first counter's capacity should be consulted.

        final ObjectCounter counterA = new BackpressureObjectCounter("test", 10, Duration.ofSeconds(1));
        final ObjectCounter counterB = new BackpressureObjectCounter("test", 5, Duration.ofSeconds(1));
        final ObjectCounter counterC = new StandardObjectCounter(Duration.ofSeconds(1));

        final MultiObjectCounter counter = new MultiObjectCounter(counterA, counterB, counterC);

        int expectedCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (expectedCount == 0 || random.nextDouble() < 0.75) {
                if (counter.attemptOnRamp()) {
                    expectedCount++;
                }
            } else {
                counter.offRamp();
                expectedCount--;
            }

            assertEquals(expectedCount, counter.getCount());
            assertEquals(expectedCount, counterA.getCount());
            assertEquals(expectedCount, counterB.getCount());
            assertEquals(expectedCount, counterC.getCount());
        }
    }

    @Test
    void forceOnRampTest() {
        final Random random = getRandomPrintSeed();

        // When attempting an on ramp, only the first counter's capacity should be consulted.

        final ObjectCounter counterA = new BackpressureObjectCounter("test", 10, Duration.ofSeconds(1));
        final ObjectCounter counterB = new BackpressureObjectCounter("test", 5, Duration.ofSeconds(1));
        final ObjectCounter counterC = new StandardObjectCounter(Duration.ofSeconds(1));

        final MultiObjectCounter counter = new MultiObjectCounter(counterA, counterB, counterC);

        int expectedCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (expectedCount == 0 || random.nextDouble() < 0.75) {
                counter.forceOnRamp();
                expectedCount++;
            } else {
                counter.offRamp();
                expectedCount--;
            }

            assertEquals(expectedCount, counter.getCount());
            assertEquals(expectedCount, counterA.getCount());
            assertEquals(expectedCount, counterB.getCount());
            assertEquals(expectedCount, counterC.getCount());
        }
    }

    @Test
    void waitUntilEmptyTest() throws InterruptedException {
        final ObjectCounter counterA = new BackpressureObjectCounter("test", 10, Duration.ofSeconds(1));
        final ObjectCounter counterB = new BackpressureObjectCounter("test", 5, Duration.ofSeconds(1));
        final ObjectCounter counterC = new StandardObjectCounter(Duration.ofSeconds(1));

        final MultiObjectCounter counter = new MultiObjectCounter(counterA, counterB, counterC);

        for (int i = 0; i < 100; i++) {
            counter.forceOnRamp();
        }

        counterB.forceOnRamp();

        counterC.forceOnRamp();
        counterC.forceOnRamp();

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

        // Remove enough things so that counterA is unblocked.
        for (int i = 0; i < 10; i++) {
            counter.offRamp();
        }

        MILLISECONDS.sleep(50);
        assertFalse(empty.get());

        // Reduce counter B to zero.
        counterB.offRamp();

        MILLISECONDS.sleep(50);
        assertFalse(empty.get());

        // Finally, remove all elements from counter C.
        counterC.offRamp();
        counterC.offRamp();

        assertEventuallyTrue(empty::get, Duration.ofSeconds(1), "Counter did not empty in time.");
    }
}
