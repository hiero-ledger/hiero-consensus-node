// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.concurrent.TestExecutor;
import com.swirlds.base.test.fixtures.concurrent.WithTestExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

@WithTestExecutor
class ShrinkableSizeCacheTest {

    private static final int SHRINK_PERIOD_IN_MS = 10;

    @Test
    void testCacheSize() throws InterruptedException {
        // given
        Lock lock = new ReentrantLock();
        Condition cleanUpDone = lock.newCondition();
        ShrinkableSizeCache<Integer, String> cache = cache(lock, cleanUpDone, 3);

        // when
        cache.put(1, "One");
        cache.put(2, "Two");
        cache.put(3, "Three");
        // then
        assertEquals(3, cache.size());

        // and when
        cache.put(4, "Four");
        lock.lock();
        cleanUpDone.await();
        lock.unlock();

        // then
        assertEquals(3, cache.size());
    }

    @Test
    void testEldestEntriesRemoval() throws InterruptedException {
        // given
        Lock lock = new ReentrantLock();
        Condition cleanUpDone = lock.newCondition();
        ShrinkableSizeCache<Integer, String> cache = cache(lock, cleanUpDone, 3);

        // when
        cache.put(1, "One");
        cache.put(2, "Two");
        cache.put(3, "Three");
        cache.put(4, "Four");
        lock.lock();
        cleanUpDone.await();
        lock.unlock();

        // then
        assertNull(cache.get(1)); // The eldest entry should be removed
    }

    @Test
    void testBasicOperations() {
        // given
        ShrinkableSizeCache<Integer, String> cache = new ShrinkableSizeCache<>(3);

        // when
        cache.put(1, "One");
        cache.put(2, "Two");
        cache.put(3, "Three");

        // then
        assertTrue(cache.containsKey(1));
        assertTrue(cache.containsValue("Two"));
        assertEquals("Three", cache.get(3));
    }

    @Test
    void testConcurrency(TestExecutor executor) {
        // given
        Lock lock = new ReentrantLock();
        Condition cleanUpDone = lock.newCondition();
        ShrinkableSizeCache<Integer, String> cache = cache(lock, cleanUpDone, 50);

        // when
        Runnable task1 = () -> rangeClosed(0, 100).forEach(i -> cache.put(i, "Value " + i));
        Runnable task2 = () -> rangeClosed(101, 200).forEach(i -> cache.put(i, "Value " + i));
        Runnable task3 = () -> rangeClosed(0, 200)
                .forEach(i -> cache.put(ThreadLocalRandom.current().nextInt(), "Random value"));

        long startTime = System.currentTimeMillis();
        executor.executeAndWait(task1, task2, task3);
        long endTime = System.currentTimeMillis();
        long waitCycles = Math.max((endTime - startTime) / SHRINK_PERIOD_IN_MS, 1);
        LongStream.rangeClosed(0, waitCycles).forEach(i -> {
            lock.lock();
            try {
                cleanUpDone.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        });

        // then
        assertEquals(50, cache.size());
    }

    /**
     * Creates a cache that can signal a condition as completed
     */
    private static ShrinkableSizeCache<Integer, String> cache(
            final Lock lock, final Condition cleanUpDone, final int maxSize) {
        return new ShrinkableSizeCache<>(maxSize, SHRINK_PERIOD_IN_MS) {
            @Override
            protected void afterUpdate() {
                lock.lock();
                try {
                    cleanUpDone.signal();
                } finally {
                    lock.unlock();
                }
            }
        };
    }
}
