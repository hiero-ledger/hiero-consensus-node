// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder.UNLIMITED_CAPACITY;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.component.framework.TestWiringModelBuilder;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConcurrentTaskSchedulerTests {

    /**
     * Add a bunch of operations to a wire and ensure that they are all eventually handled.
     */
    @Test
    void allOperationsHandledTest() {
        final WiringModel model = TestWiringModelBuilder.create();

        final Random random = getRandomPrintSeed();

        final AtomicLong count = new AtomicLong();
        final Consumer<Integer> handler = x -> {
            count.addAndGet(x);
            try {
                MICROSECONDS.sleep(random.nextInt(1000));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(TaskSchedulerType.CONCURRENT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);

        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());

        long expecterdCount = 0;
        for (int i = 0; i < 100; i++) {
            final int value = random.nextInt();
            expecterdCount += value;
            channel.put(value);
        }

        assertEventuallyEquals(expecterdCount, count::get, Duration.ofSeconds(1), "count did not reach expected value");

        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
    }

    /**
     * Verify that operations can be handled in parallel.
     */
    @Test
    void parallelOperationTest() {
        final WiringModel model = TestWiringModelBuilder.create();

        final Random random = getRandomPrintSeed();

        // Each operation has a value that needs to be added the counter.
        // Most operations will have a null latch & started variables.
        // Operations that do not have a null latch & started variables will block
        record Operation(int value, @Nullable CountDownLatch latch, @Nullable AtomicBoolean started) {}

        final AtomicLong count = new AtomicLong();
        final Consumer<Operation> handler = x -> {
            if (x.started != null) {
                x.started.set(true);
                try {
                    x.latch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            count.addAndGet(x.value);
        };

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(TaskSchedulerType.CONCURRENT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Operation, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);

        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());

        // Create two blocking operations. We should expect to see both operations started even though
        // neither operation will be able to finish.

        final CountDownLatch latch0 = new CountDownLatch(1);
        final AtomicBoolean started0 = new AtomicBoolean();
        final CountDownLatch latch1 = new CountDownLatch(1);
        final AtomicBoolean started1 = new AtomicBoolean();

        long expectedCount = 0;
        for (int i = 0; i < 100; i++) {
            final int value = random.nextInt();
            expectedCount += value;
            if (i == 0) {
                channel.put(new Operation(value, latch0, started0));
            } else if (i == 1) {
                channel.put(new Operation(value, latch1, started1));
            } else {
                channel.put(new Operation(value, null, null));
            }
        }

        assertEventuallyTrue(
                () -> started0.get() && started1.get(), Duration.ofSeconds(1), "operations did not all start");

        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());

        latch0.countDown();
        latch1.countDown();

        assertEventuallyEquals(expectedCount, count::get, Duration.ofSeconds(1), "count did not reach expected value");

        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
    }
}
