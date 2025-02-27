// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.utility.StopWatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConcurrentBitSetQueueTests {

    @Test
    void addNegativeValue() {
        final ConcurrentBitSetQueue queue = new ConcurrentBitSetQueue();
        assertThrows(
                IllegalArgumentException.class,
                () -> queue.add(-10L),
                "Only positive values (and zero) are ever added to the queue");
    }

    @Test
    void addNonIncreasingValue() {
        final ConcurrentBitSetQueue queue = new ConcurrentBitSetQueue();
        queue.add(100L);
        assertThrows(
                IllegalArgumentException.class,
                () -> queue.add(50L),
                "Each value added to the queue is strictly greater than the one before it");
    }

    @Test
    void removeEmptyQueue() {
        final ConcurrentBitSetQueue queue = new ConcurrentBitSetQueue();
        final Exception exception =
                assertThrows(IllegalStateException.class, queue::remove, "Can't remove from empty queue");
        assertEquals(
                "BitSetQueue is empty", exception.getMessage(), "Exception should be due to removal from empty queue");
    }

    @ParameterizedTest
    @ValueSource(longs = {1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000})
    void validatesSequentialInsertion(final long offset) {
        final ConcurrentBitSetQueue queue = new ConcurrentBitSetQueue();
        final long startIndex = Integer.MAX_VALUE - offset;
        final long endIndex = Integer.MAX_VALUE + offset;
        final StopWatch addWatch = new StopWatch();
        addWatch.start();
        for (long index = startIndex; index < endIndex; index++) {
            queue.add(index);
        }

        addWatch.stop();
        System.out.format(
                "Adding %d elements took %d milliseconds\n", 2 * offset, addWatch.getTime(TimeUnit.MILLISECONDS));

        final StopWatch removeWatch = new StopWatch();
        removeWatch.start();
        for (long index = startIndex; index < endIndex; index++) {
            final long value = queue.remove();
            if (value != index) {
                fail(String.format("Expected %d, but got %d", index, value));
            }
        }

        removeWatch.stop();
        System.out.format(
                "Removing %d elements took %d milliseconds\n", 2 * offset, removeWatch.getTime(TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, Integer.MAX_VALUE - 10_000, Long.MAX_VALUE - 20L * Integer.MAX_VALUE})
    void validatesSparseInsertion(final long startValue) {
        final int steps = 10;
        final ConcurrentBitSetQueue queue = new ConcurrentBitSetQueue();
        long startIndex = startValue;
        for (int index = 0; index < steps; index++) {
            long value = startIndex;
            for (long insert = 0; insert < 1_000; insert++) {
                queue.add(value);
                value++;
            }

            startIndex += Integer.MAX_VALUE;
        }

        startIndex = startValue;
        for (int index = 0; index < steps; index++) {
            long expectedValue = startIndex;
            for (long inserted = 0; inserted < 1_000; inserted++) {
                final long value = queue.remove();
                if (value != expectedValue) {
                    fail(String.format("Expected %d, but got %d", expectedValue, value));
                }
                expectedValue++;
            }

            startIndex += Integer.MAX_VALUE;
        }
    }

    @ParameterizedTest
    @CsvSource({
        "2147483547, 2147483747, 1, 4, 2",
        "2147483547, 2147483747, 1, 2, 4",
        "0, 100000000, 1, 0, 0",
        "0, 200000000, 3, 0, 0"
    })
    void simpleConsumerProducer(
            final long start,
            final long limit,
            final long step,
            final long consumerSleepInMilliseconds,
            final long producerSleepInMilliseconds)
            throws InterruptedException {
        final ConcurrentBitSetQueue queue = new ConcurrentBitSetQueue();
        final Executor executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> produceLongs(start, limit, step, producerSleepInMilliseconds, queue));
        for (long index = start; index < limit; index += step) {
            while (queue.isEmpty()) {
                Thread.onSpinWait();
            }

            final long value = queue.remove();
            if (value != index) {
                fail(String.format("Expected %d, but got %d", index, value));
            }

            TimeUnit.MILLISECONDS.sleep(consumerSleepInMilliseconds);
        }
    }

    private void produceLongs(
            final long start, final long limit, final long step, final long sleep, final ConcurrentBitSetQueue queue) {
        try {
            for (long index = start; index < limit; index += step) {
                queue.add(index);
                TimeUnit.MILLISECONDS.sleep(sleep);
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(String.format("Producer failed to sleep for %d milliseconds", sleep), ex);
        }
    }
}
