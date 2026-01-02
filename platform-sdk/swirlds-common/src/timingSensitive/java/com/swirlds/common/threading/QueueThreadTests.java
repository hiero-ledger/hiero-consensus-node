// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyFalse;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.test.fixtures.AssertionUtils.completeBeforeTimeout;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.consensus.concurrent.framework.internal.AbstractQueueThreadConfiguration.UNLIMITED_CAPACITY;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.framework.QueueThread;
import org.hiero.consensus.concurrent.framework.Stoppable;
import org.hiero.consensus.concurrent.framework.ThreadSeed;
import org.hiero.consensus.concurrent.framework.config.QueueThreadConfiguration;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.metrics.PlatformMetricsFactory;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Queue Thread Tests")
class QueueThreadTests {

    static final String THREAD_NAME = "myThread";
    static final String METRIC_CATEGORY = "myCategory";
    static final String MAX_SIZE_METRIC_NAME = THREAD_NAME + "_queueMaxSize";
    static final String MIN_SIZE_METRIC_NAME = THREAD_NAME + "_queueMinSize";

    private static Stream<Arguments> queueTypes() {
        return Stream.of(
                Arguments.of(new PriorityBlockingQueue<Integer>()),
                Arguments.of(new LinkedBlockingQueue<Integer>()),
                Arguments.of(new LinkedBlockingDeque<Integer>()),
                Arguments.of(new LinkedTransferQueue<Integer>()));
    }

    private Metrics metrics;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() {
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        executor = Executors.newSingleThreadScheduledExecutor();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final PlatformMetricsFactory factory = new PlatformMetricsFactoryImpl(metricsConfig);
        metrics = new DefaultPlatformMetrics(null, registry, executor, factory, metricsConfig);
    }

    @AfterEach
    void teardown() {
        executor.shutdown();
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Queue Capacity Test")
    void queueCapacityTest() throws InterruptedException {

        final int capacity = 10;

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setCapacity(capacity)
                .setHandler((item) -> {})
                .build();

        // Should be able to fill to capacity
        for (int index = 0; index < capacity; index++) {
            assertTrue(qt.offer(index), "should have been able to add");
        }

        // Should not be able to add one more
        assertFalse(qt.offer(1234), "queue should be full");
        assertThrows(IllegalStateException.class, () -> qt.add(1234), "expected add operation to fail");

        // Starting the queue thread should allow more items to be added
        qt.start();
        assertTrue(qt.offer(1234, 100, MILLISECONDS), "expected item to eventually be added");

        qt.stop();
        qt.join(100);
        assertFalse(qt.isAlive(), "expected thread to be dead");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Items Are Handled Test")
    void itemsAreHandledTest() throws InterruptedException {

        final AtomicInteger nextExpectedValue = new AtomicInteger(0);
        final AtomicBoolean exception = new AtomicBoolean(false);

        final Thread.UncaughtExceptionHandler exceptionHandler = (t, e) -> {
            e.printStackTrace();
            exception.set(true);
        };

        final InterruptableConsumer<Integer> handler = (Integer value) -> {
            assertEquals(nextExpectedValue.get(), value, "actual value does not match expected value");
            nextExpectedValue.getAndIncrement();
            // Force the handling thread to be slower than the thread adding things to the queue
            Thread.sleep(1);
        };

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setExceptionHandler(exceptionHandler)
                .setCapacity(10)
                .setHandler(handler)
                .build();

        qt.start();

        for (int i = 0; i < 1000; i++) {
            qt.put(i);
        }

        qt.stop();
        assertFalse(qt.isAlive(), "thread should be dead");
        assertFalse(exception.get(), "there should have been no exceptions");
        assertEquals(1000, nextExpectedValue.get(), "expected value is not correct");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Close Handles All Remaining Items Test")
    void closeHandlesAllRemainingItemsTest() throws InterruptedException {
        final AtomicInteger nextExpectedValue = new AtomicInteger(0);
        final AtomicBoolean exception = new AtomicBoolean(false);
        final Semaphore lock = new Semaphore(1, true);

        final Thread.UncaughtExceptionHandler exceptionHandler = (t, e) -> {
            e.printStackTrace();
            exception.set(true);
        };

        final InterruptableConsumer<Integer> handler = (Integer value) -> {
            lock.acquire();
            assertEquals(nextExpectedValue.get(), value, "actual value does not match expected value");
            nextExpectedValue.getAndIncrement();
            lock.release();
            // Force the handling thread to be slower than the thread adding things to the queue
            Thread.sleep(1);
        };

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setExceptionHandler(exceptionHandler)
                .setCapacity(1000)
                .setHandler(handler)
                .build();

        qt.start();

        for (int i = 0; i < 500; i++) {
            qt.put(i);
        }

        // Cause the handle thread to be blocked
        lock.acquire();

        for (int i = 500; i < 1000; i++) {
            qt.put(i);
        }

        // Close the thread before it finishes handling everything
        final Thread reaperThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(qt::stop)
                .build();
        reaperThread.start();

        qt.join(100);
        reaperThread.join(100);
        assertTrue(qt.isAlive(), "thread should still be alive");
        assertTrue(reaperThread.isAlive(), "thread should still be alive");

        // Unblock the queue thread
        lock.release();

        qt.join(1000);
        reaperThread.join(2000);
        assertFalse(qt.isAlive(), "thread should be dead");
        assertFalse(reaperThread.isAlive(), "thread should be dead");

        assertEquals(1000, nextExpectedValue.get(), "expected value is not correct");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Clear After Interrupt Test")
    void clearTestAfterInterrupt() throws InterruptedException {
        final InterruptableConsumer<Integer> handler = (final Integer item) -> {
            MILLISECONDS.sleep(10_000);
        };
        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler(handler)
                .setMaxBufferSize(1)
                .setStopBehavior(Stoppable.StopBehavior.INTERRUPTABLE)
                .build();

        qt.put(1);
        qt.put(2);
        qt.start();

        MILLISECONDS.sleep(100);

        qt.stop();

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            qt.clear();
            return null;
        });

        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException e) {
            fail("clear() hung on stopped thread queue.");
        }
        assertEquals(0, qt.size());
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Clear Test")
    void clearTest() throws InterruptedException {

        final AtomicInteger handledValue = new AtomicInteger(-1);

        final InterruptableConsumer<Integer> handler = (final Integer item) -> {
            Thread.sleep(5);
            handledValue.set(item);
        };

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(10)
                .setCapacity(1000)
                .setHandler(handler)
                .build();

        // fill up the queue
        for (int i = 0; i < 1000; i++) {
            qt.put(i);
        }

        // start handling
        qt.start();
        assertEventuallyTrue(
                () -> handledValue.get() > -1, Duration.ofSeconds(1), "expected values to have been handled");

        // clear the queue, high probability that there significant work in the buffer,
        // and much more than 100ms still in the queue.
        // After clear, there should be no work still being done.
        qt.clear();
        assertEquals(0, qt.size(), "queue should be empty");

        final int secondReading = handledValue.get();
        Thread.sleep(10);
        final int thirdReading = handledValue.get();

        assertEquals(secondReading, thirdReading, "there should be no work still being handled");
        assertTrue(thirdReading < 999, "there shouldn't have been enough time to handle all items");

        // Adding more work to the queue should cause it to be handled
        for (int i = 0; i < 5; i++) {
            qt.put(i * 1000);
        }

        assertEventuallyTrue(() -> handledValue.get() > 999, Duration.ofSeconds(1), "new items should be handled");

        qt.stop();
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("UnlimitedCapacityTest Test")
    void unlimitedCapacityTest() throws InterruptedException {

        final QueueThread<Integer> queueThread = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setUnlimitedCapacity()
                .setHandler((i) -> {})
                .build();

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    for (int i = 0; i < 1_000; i++) {
                        queueThread.add(i);
                    }
                })
                .build();
        thread.start();

        thread.join(1000);
        assertFalse(thread.isAlive(), "thread should have finished by now");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("InterruptableTest")
    void interruptableTest() throws InterruptedException {
        final Semaphore lock = new Semaphore(1);
        final InterruptableConsumer<Integer> handler = (value) -> lock.acquire();

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler(handler)
                .setStopBehavior(Stoppable.StopBehavior.INTERRUPTABLE)
                .build();

        // force the queue thread to wait until this is released
        lock.acquire();

        // thread will wait trying to acquire the lock
        qt.start();
        qt.add(1);

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            qt.stop();
            return null;
        });

        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException e) {
            fail("QueueThread was configured to be interruptable but could not be interrupted.");
        }

        assertFalse(qt.isAlive(), "The queue thread should not be alive after being stopped.");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("UninterruptableTest")
    void uninterruptableTest() throws InterruptedException {
        final Semaphore lock = new Semaphore(1);
        final InterruptableConsumer<Integer> handler = (value) -> lock.acquire();

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler(handler)
                .build();

        // force the queue thread to wait until this is released
        lock.acquire();

        // thread will wait trying to acquire the lock
        qt.start();
        qt.add(1);

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            qt.stop();
            return null;
        });

        assertThrows(TimeoutException.class, () -> future.get(100, MILLISECONDS), "Thread shouldn't be interruptable");

        // Thread finally closes with manual interrupt
        qt.interrupt();

        assertEventuallyFalse(
                qt::isAlive, Duration.ofSeconds(1), "The queue thread should not be alive after being stopped.");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Blocking Stop Override Test")
    void blockingStopOverrideTest() throws InterruptedException {
        final Semaphore lock = new Semaphore(1);
        final InterruptableConsumer<Integer> handler = (value) -> lock.acquire();

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler(handler)
                .setStopBehavior(Stoppable.StopBehavior.INTERRUPTABLE)
                .build();

        // force the queue thread to wait until this is released
        lock.acquire();

        // thread will wait trying to acquire the lock
        qt.start();
        qt.add(1);

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            // Stop with blocking behavior instead of default interruptable behavior
            qt.stop(Stoppable.StopBehavior.BLOCKING);
            return null;
        });

        assertThrows(TimeoutException.class, () -> future.get(100, MILLISECONDS), "Thread should be blocking");

        // Thread finally closes with manual interrupt
        qt.interrupt();

        assertEventuallyFalse(
                qt::isAlive, Duration.ofSeconds(1), "The queue thread should not be alive after being stopped.");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Interruptable Stop Override Test")
    void interruptableStopOverrideTest() throws InterruptedException, ExecutionException, TimeoutException {
        final Semaphore lock = new Semaphore(1);
        final InterruptableConsumer<Integer> handler = (value) -> lock.acquire();

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler(handler)
                .build();

        // force the queue thread to wait until this is released
        lock.acquire();

        // thread will wait trying to acquire the lock
        qt.start();
        qt.add(1);

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<Void> future = executorService.submit(() -> {
            // Stop with interruptable behavior instead of default blocking behavior
            qt.stop(Stoppable.StopBehavior.INTERRUPTABLE);
            return null;
        });

        future.get(100, MILLISECONDS);

        assertFalse(qt.isAlive(), "The queue thread should not be alive after being stopped.");
    }

    @ParameterizedTest
    @MethodSource("queueTypes")
    @Tag(TestComponentTags.THREADING)
    @DisplayName("QueueTest")
    void queueTest(final BlockingQueue<Integer> queue) throws InterruptedException {

        final Queue<Integer> handledInts = new LinkedList<>();

        final QueueThread<Integer> qt = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setQueue(queue)
                .setHandler(handledInts::add)
                .build();

        qt.start();

        IntStream.range(0, 100).boxed().forEach(qt::add);
        MILLISECONDS.sleep(50);

        qt.stop();

        assertEquals(100, handledInts.size());
    }

    @Test
    @DisplayName("Seed Test")
    void seedTest() throws InterruptedException {
        final AtomicLong count = new AtomicLong();
        final AtomicBoolean enableLongSleep = new AtomicBoolean();
        final CountDownLatch longSleepStarted = new CountDownLatch(1);

        final QueueThread<Integer> queueThread = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName("queue-thread")
                .setUnlimitedCapacity()
                .setStopBehavior(Stoppable.StopBehavior.INTERRUPTABLE)
                .setHandler((final Integer next) -> {
                    count.set(next);
                    // Disable long sleep for subsequent calls
                    if (enableLongSleep.getAndSet(false)) {
                        longSleepStarted.countDown();
                        SECONDS.sleep(999999999);
                    }
                })
                .build();

        final ThreadSeed seed = queueThread.buildSeed();

        final AtomicBoolean seedHasYieldedControl = new AtomicBoolean();
        final CountDownLatch exitLatch = new CountDownLatch(1);

        // This thread will have the seed injected into it.
        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setThreadName("inject-into-this-thread")
                .setInterruptableRunnable(() -> {
                    // The seed will take over this thread for a while

                    seed.inject();

                    seedHasYieldedControl.set(true);
                    exitLatch.await();
                })
                .build(true);

        assertEventuallyTrue(
                () -> thread.getName().equals("<queue-thread>"),
                Duration.ofSeconds(1),
                "queue thread should eventually take over");

        for (int i = 0; i < 1001; i++) {
            queueThread.add(i);
        }

        assertEventuallyTrue(
                () -> count.get() >= 1_000, Duration.ofSeconds(1), "count should have increased more by now");

        enableLongSleep.set(true);
        for (int i = 1001; i < 2001; i++) {
            queueThread.add(i);
        }
        longSleepStarted.await();

        queueThread.stop();

        assertEventuallyTrue(seedHasYieldedControl::get, Duration.ofSeconds(1), "seed should have yielded");
        assertEquals("<inject-into-this-thread>", thread.getName(), "original settings should have been restored");

        exitLatch.countDown();
    }

    @Test
    @DisplayName("Configuration Mutability Test")
    void configurationMutabilityTest() {
        // Build should make the configuration immutable
        final QueueThreadConfiguration<Integer> configuration = new QueueThreadConfiguration<Integer>(
                        getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler((final Integer element) -> {});

        assertTrue(configuration.isMutable(), "configuration should be mutable");

        configuration.build();
        assertTrue(configuration.isImmutable(), "configuration should be immutable");

        assertThrows(
                MutabilityException.class, () -> configuration.setCapacity(1234), "configuration should be immutable");
        assertThrows(
                MutabilityException.class, configuration::setUnlimitedCapacity, "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration.setHandler(null), "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration.setQueue(null), "configuration should be immutable");
    }

    @Test
    @DisplayName("Single Use Per Config Test")
    void singleUsePerConfigTest() {

        // build() should cause future calls to build() to fail, and start() should cause buildSeed() to fail.
        final QueueThreadConfiguration<?> configuration0 = new QueueThreadConfiguration<Integer>(
                        getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler((final Integer i) -> {
                    MILLISECONDS.sleep(1);
                });

        final QueueThread<?> queueThread0 = configuration0.build();

        assertThrows(MutabilityException.class, configuration0::build, "configuration has already been used");

        queueThread0.start();

        assertThrows(IllegalStateException.class, queueThread0::buildSeed, "configuration has already been used");

        queueThread0.stop();

        // buildSeed() should cause future calls to buildSeed() and start() to fail.
        final QueueThreadConfiguration<?> configuration1 = new QueueThreadConfiguration<Integer>(
                        getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setHandler((final Integer i) -> {
                    MILLISECONDS.sleep(1);
                });

        final QueueThread<?> queueThread1 = configuration1.build();
        queueThread1.buildSeed();

        assertThrows(IllegalStateException.class, queueThread1::buildSeed, "configuration has already been used");
        assertThrows(IllegalStateException.class, queueThread1::start, "configuration has already been used");
    }

    @Test
    @DisplayName("Copy Test")
    void copyTest() {
        final InterruptableConsumer<Integer> handler = (final Integer x) -> {};

        final QueueThreadConfiguration<?> configuration = new QueueThreadConfiguration<Integer>(
                        getStaticThreadManager())
                .setThreadName(THREAD_NAME)
                .setCapacity(1234)
                .setMaxBufferSize(1234)
                .setHandler(handler)
                .setQueue(new LinkedBlockingDeque<>());

        final QueueThreadConfiguration<?> copy1 = configuration.copy();

        assertEquals(configuration.getCapacity(), copy1.getCapacity(), "copy configuration should match");
        assertEquals(configuration.getMaxBufferSize(), copy1.getMaxBufferSize(), "copy configuration should match");
        assertSame(configuration.getHandler(), copy1.getHandler(), "copy configuration should match");
        assertSame(configuration.getQueue(), copy1.getQueue(), "copy configuration should match");

        // It shouldn't matter if the original is immutable.
        configuration.build();

        final QueueThreadConfiguration<?> copy2 = configuration.copy();
        assertTrue(copy2.isMutable(), "copy should be mutable");

        assertEquals(configuration.getCapacity(), copy2.getCapacity(), "copy configuration should match");
        assertEquals(configuration.getMaxBufferSize(), copy2.getMaxBufferSize(), "copy configuration should match");
        assertSame(configuration.getHandler(), copy2.getHandler(), "copy configuration should match");
        assertSame(configuration.getQueue(), copy2.getQueue(), "copy configuration should match");
    }

    @Test
    @DisplayName("waitUntilNotBusy() Test")
    void waitUntilNotBusyTest() throws InterruptedException {

        final QueueThread<Runnable> queue = new QueueThreadConfiguration<Runnable>(getStaticThreadManager())
                .setThreadName("test")
                .setHandler(Runnable::run)
                .build(true);

        // waiting on an empty queue should not block
        completeBeforeTimeout(
                queue::waitUntilNotBusy,
                Duration.ofSeconds(1),
                "waitUntilNotBusy() should not block on an empty queue");

        final CountDownLatch queueBlockingLatch = new CountDownLatch(1);
        queue.add(() -> {
            try {
                queueBlockingLatch.await();
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        // The default max queue size is 100, and we just added one item. Do not add
        // more than 99 or the queue may throw because it is full.
        for (int i = 0; i < 99; i++) {
            queue.add(() -> {});
        }

        // Waiting on the queue should block until we release the latch
        final CountDownLatch finishedWaitingLatch = new CountDownLatch(1);
        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        queue.waitUntilNotBusy();
                        finishedWaitingLatch.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                })
                .build(true);

        assertFalse(finishedWaitingLatch.await(100, MILLISECONDS));

        // Once we unblock the queue, we should expect the waitUntilNotBusy() call to return
        queueBlockingLatch.countDown();
        assertTrue(finishedWaitingLatch.await(100, MILLISECONDS));

        queue.stop();
    }

    @Test
    @DisplayName("Idle Callback Test")
    void idleCallbackTest() throws InterruptedException {
        final AtomicBoolean error = new AtomicBoolean(false);

        final AtomicBoolean idleCallbackPermitted = new AtomicBoolean(false);
        final AtomicBoolean idleCallbackCalled = new AtomicBoolean(false);
        final InterruptableRunnable idleCallback = () -> {
            if (idleCallbackPermitted.get()) {
                idleCallbackCalled.set(true);
            } else {
                error.set(true);
            }
        };

        final QueueThread<Runnable> queue = new QueueThreadConfiguration<Runnable>(getStaticThreadManager())
                .setThreadName("test")
                .setIdleCallback(idleCallback)
                .setHandler(Runnable::run)
                .setWaitForWorkDuration(Duration.ofMillis(1))
                .build();

        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        final CountDownLatch latch3 = new CountDownLatch(1);

        queue.add(() -> {
            try {
                latch1.await();
            } catch (final InterruptedException ignored) {
                error.set(true);
                Thread.currentThread().interrupt();
            }
        });
        queue.add(() -> {
            try {
                latch2.await();
            } catch (final InterruptedException ignored) {
                error.set(true);
                Thread.currentThread().interrupt();
            }
        });
        queue.add(() -> {
            try {
                latch3.await();
            } catch (final InterruptedException ignored) {
                error.set(true);
                Thread.currentThread().interrupt();
            }
        });
        queue.start();

        // The queue should not call the idle callback during this time,
        // but give it some time to do bad things if it's going to do bad things.
        MILLISECONDS.sleep(10);

        latch1.countDown();

        // The queue should not call the idle callback during this time,
        // but give it some time to do bad things if it's going to do bad things.
        MILLISECONDS.sleep(10);

        latch2.countDown();

        // The queue should not call the idle callback during this time,
        // but give it some time to do bad things if it's going to do bad things.
        MILLISECONDS.sleep(10);

        // Once job 3 is permitted to complete, we expect for the idle callback to be invoked shortly afterward.
        idleCallbackPermitted.set(true);

        latch3.countDown();

        assertEventuallyTrue(idleCallbackCalled::get, Duration.ofSeconds(1), "Idle callback was not called");

        queue.stop();

        assertFalse(error.get());
    }

    @Test
    void batchCompletedCallbackTest() throws InterruptedException {
        final AtomicInteger count = new AtomicInteger(0);

        final int bufferSize = 100;

        final QueueThread<Integer> queue = new QueueThreadConfiguration<Integer>(getStaticThreadManager())
                .setThreadName("test")
                .setBatchHandledCallback(count::getAndIncrement)
                .setHandler(x -> {})
                .setCapacity(UNLIMITED_CAPACITY)
                .setMaxBufferSize(bufferSize)
                .build();

        // Add a bunch of stuff to the queue. Things haven't started yet, so we shouldn't have any callbacks.
        for (int i = 0; i < bufferSize; i++) {
            queue.add(i);
        }

        assertEquals(0, count.get());

        // Start the queue. We should see the batch complete callback exactly once, since all 100 items will fit
        // into the buffer.

        queue.start();

        assertEventuallyEquals(1, count::get, Duration.ofSeconds(1), "Batch completed callback was not called");

        // Wait for a while. Callback should not be called, but give the thread time to misbehave it wants to.
        MILLISECONDS.sleep(10);
        assertEquals(1, count.get());

        // Adding just a single element should cause the callback to be called again.
        queue.add(42);

        assertEventuallyEquals(2, count::get, Duration.ofSeconds(1), "Batch completed callback was not called");

        // Wait for a while. Callback should not be called, but give the thread time to misbehave it wants to.
        MILLISECONDS.sleep(10);
        assertEquals(2, count.get());

        // Add a bunch of stuff. Any number of callbacks between 1
        // and the number of elements divided by buffer size is legal.
        final int amountToAdd = 10_000;
        for (int i = 0; i < amountToAdd; i++) {
            queue.add(i);
        }

        final int minCount = 2 + (amountToAdd / bufferSize);
        final int maxCount = 2 + amountToAdd;

        assertEventuallyTrue(
                () -> count.get() >= minCount,
                Duration.ofSeconds(1),
                "Batch completed callback was not called enough times");

        // Give the thread some time to misbehave if it wants to.
        MILLISECONDS.sleep(10);

        assertTrue(count.get() <= maxCount, "Batch completed callback was called too many times");

        queue.stop();
    }
}
