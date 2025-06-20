// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StandardObjectCounterTests {

    @Test
    void basicOperationTest() {
        final Random random = getRandomPrintSeed();

        final ObjectCounter counter = new StandardObjectCounter(Duration.ofMillis(1));

        int count = 0;
        for (int i = 0; i < 1000; i++) {

            final boolean increment = count == 0 || random.nextBoolean();

            if (increment) {
                count++;

                // All of these methods are logically equivalent for this implementation.
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

    @Test
    void waitUntilEmptyTest() throws InterruptedException {
        final ObjectCounter counter = new StandardObjectCounter(Duration.ofMillis(1));

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

        assertEventuallyTrue(empty::get, Duration.ofSeconds(1), "Counter did not empty in time.");
    }
}
