// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.completeBeforeTimeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;
import org.junit.jupiter.api.Test;

class OSHealthCheckUtilsTests {

    @Test
    void testTimeout() {
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final Supplier<Boolean> supplier = () -> {
            try {
                doneLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return true;
        };

        assertEventuallyEquals(
                null,
                () -> {
                    try {
                        return OSHealthCheckUtils.timeSupplier(supplier, 100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                },
                Duration.ofMillis(200),
                "");

        doneLatch.countDown();
    }

    @Test
    void testCompletesInTime() throws InterruptedException {
        final Random r = RandomUtils.getRandom();
        final int randomInt = r.nextInt();
        final Supplier<Integer> supplier = () -> randomInt;

        final AtomicReference<OSHealthCheckUtils.SupplierResult<Integer>> checkResult = new AtomicReference<>();

        final InterruptableRunnable runCheck = () -> checkResult.set(OSHealthCheckUtils.timeSupplier(supplier, 100));

        completeBeforeTimeout(runCheck, Duration.ofMillis(300), "Check should have completed by now");

        assertNotNull(checkResult.get(), "Timout result (null) not excepted");
        assertEquals(randomInt, checkResult.get().result(), "Invalid result");
    }
}
