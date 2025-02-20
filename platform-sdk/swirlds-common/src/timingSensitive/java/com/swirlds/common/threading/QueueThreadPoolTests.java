// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyFalse;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.threading.framework.QueueThreadPool;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.ThreadSeed;
import com.swirlds.common.threading.framework.config.QueueThreadPoolConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QueueThreadPool Tests")
class QueueThreadPoolTests {

    @Test
    @DisplayName("Parallel Work Test")
    void parallelWorkTest() throws InterruptedException {

        final AtomicInteger sleepCount = new AtomicInteger();

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(1)
                .setHandler((final Integer value) -> {
                    MILLISECONDS.sleep(value);
                    sleepCount.getAndAdd(value);
                })
                .build(true);

        final Instant start = Instant.now();

        int sum = 0;
        for (int i = 0; i < 100; i++) {
            final int value = i % 2 == 0 ? 1 : 2;
            sum += value;
            pool.add(value);
        }

        assertEventuallyEquals(sum, sleepCount::get, Duration.ofSeconds(5), "work should have been handled by now");

        final Instant finished = Instant.now();
        final Duration elapsed = Duration.between(start, finished);

        assertTrue(elapsed.toMillis() < 0.5 * sleepCount.get(), "should have been much faster");

        pool.stop();
        pool.join();
    }

    @Test
    @DisplayName("Parallel Work Test")
    void pauseTest() throws InterruptedException {

        final AtomicLong count = new AtomicLong();
        final AtomicBoolean doSleep = new AtomicBoolean(true);

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(1)
                .setUnlimitedCapacity()
                .setHandler((final Integer value) -> {
                    count.getAndAdd(value);
                    if (doSleep.get()) {
                        MILLISECONDS.sleep(1);
                    }
                })
                .build(true);

        long sum = 0;
        for (int i = 0; i < 10_000; i++) {
            pool.add(i);
            sum += i;
        }

        assertEventuallyTrue(() -> count.get() > 1000, Duration.ofSeconds(1), "count should have increased by now");

        pool.pause();

        // Ensure that there is available work, just in case all work has already been handled.
        for (int i = 0; i < 10_000; i++) {
            pool.add(i);
            sum += i;
        }

        final long count1 = count.get();

        // If the threads are not paused, then this is more than enough time for them to increment the counter.
        MILLISECONDS.sleep(100);

        final long count2 = count.get();

        assertEquals(count1, count2, "threads should have been paused");

        pool.resume();
        assertEventuallyTrue(
                () -> count.get() > count2 + 1000, Duration.ofSeconds(1), "count should have increased by now");

        // Pause again to test that stopping while paused works properly
        pool.pause();

        // When stopped the threads will attempt to handle the remaining work. Make sure that work doesn't a long time.
        doSleep.set(false);

        pool.stop();
        pool.join();

        assertEquals(sum, count.get(), "all values should have been handled");
    }

    @Test
    @DisplayName("Seed Test")
    void seedTest() throws InterruptedException {

        final AtomicLong count = new AtomicLong();
        final AtomicBoolean doSleep = new AtomicBoolean(true);

        final Set<Thread> threads = Collections.synchronizedSet(new HashSet<>());
        final Set<String> threadsNames = Collections.synchronizedSet(new HashSet<>());

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(10)
                .setUnlimitedCapacity()
                .setHandler((final Integer value) -> {
                    final Thread t = Thread.currentThread();
                    threads.add(t);
                    threadsNames.add(t.getName());
                    count.getAndAdd(value);
                    if (doSleep.get()) {
                        MILLISECONDS.sleep(1);
                    }
                })
                .build();

        final List<ThreadSeed> seeds = pool.buildSeeds();

        long sum = 0;
        for (int i = 0; i < 10_000; i++) {
            pool.add(i);
            sum += i;
        }

        // Threads should not be started. This is long enough for them to do work if that assumption is not correct.
        MILLISECONDS.sleep(100);
        assertEquals(0, count.get(), "no work should have been done yet.");

        // Inject seeds into executor service
        final ExecutorService executor = Executors.newFixedThreadPool(10);
        for (final ThreadSeed seed : seeds) {
            executor.execute(seed::inject);
        }

        assertEventuallyTrue(() -> count.get() > 1000, Duration.ofSeconds(1), "count should have increased by now");

        pool.stop();

        // The threads should still be alive, but join shouldn't block now that the work is over!
        pool.join();

        assertEquals(sum, count.get(), "count should match");

        // Validate thread names during execution.
        assertEquals(11, threadsNames.size(), "unexpected number of unique thread names");
        for (final String threadName : threadsNames) {
            assertTrue(
                    threadName.contains("<test: worker #")
                            || threadName.equals("main")
                            || threadName.equals("Test worker"),
                    "unexpected thread name " + threadName);
        }

        // All of the work group threads should still be alive.
        for (final Thread thread : threads) {
            assertTrue(thread.isAlive(), "thread pool thread should still be alive");
        }

        // Make sure that the original thread name was restored as expected
        for (final Thread thread : threads) {
            assertFalse(thread.getName().contains("<test: worker #"), "thread name should have been reverted");
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Configuration Mutability Test")
    void configurationMutabilityTest() {
        // Build should make the configuration immutable
        final QueueThreadPoolConfiguration<Integer> configuration = new QueueThreadPoolConfiguration<Integer>(
                        getStaticThreadManager())
                .setHandler((final Integer element) -> {});

        assertTrue(configuration.isMutable(), "configuration should be mutable");

        configuration.build();
        assertTrue(configuration.isImmutable(), "configuration should be immutable");

        assertThrows(
                MutabilityException.class,
                () -> configuration.setThreadCount(1234),
                "configuration should be immutable");
    }

    @Test
    @DisplayName("Single Use Per Config Test")
    void singleUsePerConfigTest() {

        // build() should cause future calls to build() to fail, and start() should cause buildSeed() to fail.
        final QueueThreadPoolConfiguration<?> configuration0 = new QueueThreadPoolConfiguration<Integer>(
                        getStaticThreadManager())
                .setHandler((final Integer i) -> {
                    MILLISECONDS.sleep(1);
                });

        final QueueThreadPool<?> queueThread0 = configuration0.build();

        assertThrows(MutabilityException.class, configuration0::build, "configuration has already been used");

        queueThread0.start();

        assertThrows(IllegalStateException.class, queueThread0::buildSeeds, "configuration has already been used");

        queueThread0.stop();

        // buildSeed() should cause future calls to buildSeed() and start() to fail.
        final QueueThreadPoolConfiguration<?> configuration1 = new QueueThreadPoolConfiguration<Integer>(
                        getStaticThreadManager())
                .setHandler((final Integer i) -> {
                    MILLISECONDS.sleep(1);
                });

        final QueueThreadPool<?> queueThread1 = configuration1.build();
        queueThread1.buildSeeds();

        assertThrows(IllegalStateException.class, queueThread1::buildSeeds, "configuration has already been used");
        assertThrows(IllegalStateException.class, queueThread1::start, "configuration has already been used");
    }

    @Test
    @DisplayName("Copy Test")
    void copyTest() {
        final QueueThreadPoolConfiguration<?> configuration =
                new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager()).setThreadCount(1234);

        final QueueThreadPoolConfiguration<?> copy1 = configuration.copy();

        assertEquals(configuration.getThreadCount(), copy1.getThreadCount(), "copy configuration should match");

        // It shouldn't matter if the original is immutable.
        configuration.build();

        final QueueThreadPoolConfiguration<?> copy2 = configuration.copy();
        assertTrue(copy2.isMutable(), "copy should be mutable");

        assertEquals(configuration.getThreadCount(), copy2.getThreadCount(), "copy configuration should match");
    }

    @Test
    @DisplayName("Interruptable Test")
    void interruptableTest() throws InterruptedException, ExecutionException, TimeoutException {
        final Set<Thread> threads = Collections.synchronizedSet(new HashSet<>());

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(1)
                .setHandler((final Integer i) -> {
                    final Thread t = Thread.currentThread();
                    threads.add(t);
                    MILLISECONDS.sleep(1_000_000_000);
                })
                .setStopBehavior(Stoppable.StopBehavior.INTERRUPTABLE)
                .build(true);

        for (int i = 0; i < 10; i++) {
            pool.add(i);
        }

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            pool.stop();
            return null;
        });

        future.get(1000, MILLISECONDS);

        for (final Thread thread : threads) {
            assertFalse(thread.isAlive(), "thread pool thread should be dead");
        }
    }

    @Test
    @DisplayName("Uninterruptable Test")
    void uninterruptableTest() throws InterruptedException {
        final Set<Thread> threads = Collections.synchronizedSet(new HashSet<>());

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(1)
                .setHandler((final Integer i) -> {
                    final Thread t = Thread.currentThread();
                    threads.add(t);
                    MILLISECONDS.sleep(1_000_000_000);
                })
                .build(true);

        for (int i = 0; i < 10; i++) {
            pool.add(i);
        }

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            pool.stop();
            return null;
        });

        assertThrows(
                TimeoutException.class, () -> future.get(100, MILLISECONDS), "Thread pool shouldn't be interruptable");

        // Manually interrupt threads
        for (final Thread thread : threads) {
            thread.interrupt();
        }

        for (final Thread thread : threads) {
            assertEventuallyFalse(thread::isAlive, Duration.ofSeconds(1), "thread should be dead by now");
        }
    }

    @Test
    @DisplayName("Blocking Stop Override Test")
    void blockingStopOverrideTest() throws InterruptedException {
        final Set<Thread> threads = Collections.synchronizedSet(new HashSet<>());

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(1)
                .setHandler((final Integer i) -> {
                    final Thread t = Thread.currentThread();
                    threads.add(t);
                    MILLISECONDS.sleep(1_000_000_000);
                })
                .setStopBehavior(Stoppable.StopBehavior.INTERRUPTABLE)
                .build(true);

        for (int i = 0; i < 10; i++) {
            pool.add(i);
        }

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            // Stop with blocking behavior instead of default interruptable behavior
            pool.stop(Stoppable.StopBehavior.BLOCKING);
            return null;
        });

        assertThrows(
                TimeoutException.class,
                () -> future.get(100, MILLISECONDS),
                "Thread pool shouldn't have been interrupted");

        // Manually interrupt threads
        for (final Thread thread : threads) {
            thread.interrupt();
        }

        for (final Thread thread : threads) {
            assertEventuallyFalse(thread::isAlive, Duration.ofSeconds(1), "thread should be dead by now");
        }
    }

    @Test
    @DisplayName("Interruptable Stop Override Test")
    void interruptableStopOverrideTest() throws InterruptedException, ExecutionException, TimeoutException {
        final Set<Thread> threads = new HashSet<>();

        final QueueThreadPool<Integer> pool = new QueueThreadPoolConfiguration<Integer>(getStaticThreadManager())
                .setComponent("test")
                .setThreadName("worker")
                .setThreadCount(10)
                .setMaxBufferSize(1)
                .setHandler((final Integer i) -> {
                    final Thread t = Thread.currentThread();
                    threads.add(t);
                    MILLISECONDS.sleep(1_000_000_000);
                })
                .build(true);

        for (int i = 0; i < 10; i++) {
            pool.add(i);
        }

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            // Stop with interruptable behavior instead of default blocking behavior
            pool.stop(Stoppable.StopBehavior.INTERRUPTABLE);
            return null;
        });

        future.get(1000, MILLISECONDS);

        for (final Thread thread : threads) {
            assertFalse(thread.isAlive(), "thread pool thread should be dead");
        }
    }
}
