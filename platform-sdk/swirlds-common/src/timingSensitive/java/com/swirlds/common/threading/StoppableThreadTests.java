// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyFalse;
import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.ThreadSeed;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Stoppable Thread Tests")
class StoppableThreadTests {

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Test Interruptable Thread")
    void testInterruptableThread() throws InterruptedException {
        final StoppableThread runawayThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setThreadName("runaway thread")
                .setWork(() -> Thread.sleep(1_000_000_000))
                .build();

        runawayThread.start();
        // Give the thread a chance to start.
        Thread.sleep(10);

        runawayThread.stop();
        runawayThread.join(100);
        assertFalse(runawayThread.isAlive(), "thread should have been interrupted");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Test Uninterruptable Thread")
    void testUninterruptableThread() throws InterruptedException {

        // This thread will run for a long time
        final StoppableThread runawayThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setThreadName("runaway thread")
                .setStopBehavior(Stoppable.StopBehavior.BLOCKING)
                .setWork(() -> Thread.sleep(1_000_000_000))
                .build();

        // This thread will attempt to close the runaway thread. When the runaway thread dies then this thread dies.
        final Thread reaperThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(runawayThread::stop)
                .build();

        runawayThread.start();
        // Make sure the runaway thread enters its long sleep before starting the reaper thread
        Thread.sleep(10);
        reaperThread.start();

        // Reaper thread will block since runaway thread doesn't interrupt on close
        reaperThread.join(100);
        assertTrue(runawayThread.isAlive(), "thread should still be alive");
        assertTrue(reaperThread.isAlive(), "thread should still be alive");

        // When we manually interrupt the runaway thread it will finally close
        runawayThread.interrupt();
        runawayThread.join(100);
        reaperThread.join(100);
        assertFalse(runawayThread.isAlive(), "expected thread to be dead");
        assertFalse(reaperThread.isAlive(), "expected thread to be dead");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Test Blocking Stop Override")
    void testBlockingStopOverride() throws InterruptedException {
        final CountDownLatch threadStarted = new CountDownLatch(1);

        // This thread will run for a long time
        final StoppableThread runawayThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setThreadName("runaway thread")
                .setWork(() -> {
                    threadStarted.countDown();
                    MILLISECONDS.sleep(1_000_000_000);
                })
                .build();

        // This thread will attempt to close the runaway thread with blocking behavior, which will override the
        // default interruptable behavior. When the runaway thread dies then this thread dies.
        final Thread reaperThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> runawayThread.stop(Stoppable.StopBehavior.BLOCKING))
                .build();

        runawayThread.start();
        threadStarted.await();
        reaperThread.start();

        // Reaper thread will block since runaway thread doesn't interrupt on close
        reaperThread.join(100);
        assertTrue(runawayThread.isAlive(), "thread should still be alive");
        assertTrue(reaperThread.isAlive(), "thread should still be alive");

        // When we manually interrupt the runaway thread it will finally close
        runawayThread.interrupt();

        assertEventuallyFalse(runawayThread::isAlive, Duration.ofSeconds(1), "expected thread to be dead");
        assertEventuallyFalse(reaperThread::isAlive, Duration.ofSeconds(1), "expected thread to be dead");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Test Interrupting Stop Override")
    void testInterruptingStopOverride() throws InterruptedException {
        final CountDownLatch threadStarted = new CountDownLatch(1);

        // This thread will run for a long time
        final StoppableThread runawayThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setThreadName("runaway thread")
                .setStopBehavior(Stoppable.StopBehavior.BLOCKING)
                .setWork(() -> {
                    threadStarted.countDown();
                    MILLISECONDS.sleep(1_000_000_000);
                })
                .build(true);

        threadStarted.await();

        // Stop the thread with interruptable behavior, which will override the default blocking behavior
        runawayThread.stop(Stoppable.StopBehavior.INTERRUPTABLE);

        assertEventuallyFalse(runawayThread::isAlive, Duration.ofSeconds(1), "thread should have been interrupted");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Final Run Test")
    void finalRunTest(final boolean doFinalCycle) throws InterruptedException {

        final AtomicInteger count = new AtomicInteger(0);
        final Semaphore lock = new Semaphore(1);
        final AtomicBoolean finalCycle = new AtomicBoolean(false);

        final InterruptableRunnable work = () -> {
            lock.acquire();
            count.getAndIncrement();
        };
        final InterruptableRunnable finalCycleWork = doFinalCycle
                ? () -> {
                    assertFalse(finalCycle.get(), "final cycle should only happen once");
                    finalCycle.set(true);
                    work.run();
                }
                : null;

        // Each cycle, this thread takes a lock and increments a number.
        // Thread does not release lock, allowing outside context to control
        // how frequently it cycles.
        final StoppableThread thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setThreadName("test-thread")
                .setStopBehavior(Stoppable.StopBehavior.BLOCKING)
                .setFinalCycleWork(finalCycleWork)
                .setWork(work)
                .build();

        assertEquals(0, count.get(), "count should still be 0");

        thread.start();

        assertEventuallyEquals(1, count::get, Duration.ofSeconds(1), "expected count to have been incremented");

        lock.release();
        assertEventuallyEquals(2, count::get, Duration.ofSeconds(1), "expected count to have been incremented");

        lock.release();
        assertEventuallyEquals(3, count::get, Duration.ofSeconds(1), "expected count to have been incremented");

        // Give the thread enough time to circle around and get blocked on the lock.
        MILLISECONDS.sleep(100);

        final Thread reaperThread = new ThreadConfiguration(getStaticThreadManager())
                .setThreadName("reaper")
                .setRunnable(thread::stop)
                .build(true);

        assertEventuallyEquals(
                StoppableThread.Status.DYING,
                thread::getStatus,
                Duration.ofSeconds(1),
                "thread should be dying by now");

        // Thread is stuck on iteration from before close until the lock is cycled
        assertTrue(thread.isAlive(), "thread should still be running");
        assertTrue(reaperThread.isAlive(), "thread should still be running");
        assertEquals(3, count.get(), "expected count to stay the same");

        // Cycle the lock, allowing the iteration that started before the close call to complete
        lock.release();
        thread.join();

        assertEquals(4, count.get(), "expected count to have been incremented");

        assertFalse(thread.isAlive(), "thread should be dead");

        if (doFinalCycle) {
            assertTrue(reaperThread.isAlive(), "thread should still be running");

            // There is a final run. We must cycle the lock one final time.
            lock.release();
            reaperThread.join();
            assertEquals(5, count.get(), "expected count to have been incremented");
            assertTrue(finalCycle.get(), "final cycle method should have been run");

        } else {
            reaperThread.join();
            assertFalse(reaperThread.isAlive(), "thread should be dead");
        }
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Pause Test")
    void pauseTest() throws InterruptedException {

        final AtomicInteger count = new AtomicInteger(0);

        final StoppableThread thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(count::getAndIncrement)
                .build();

        thread.start();

        final int firstValueRead = count.get();
        assertEventuallyTrue(
                () -> {
                    final int secondValueRead = count.get();
                    return secondValueRead > firstValueRead;
                },
                Duration.ofSeconds(1),
                "expected count to have been increased");

        thread.pause();
        final int thirdValueRead = count.get();
        Thread.sleep(50);

        final int fourthValueRead = count.get();
        assertEquals(thirdValueRead, fourthValueRead, "expected count to not change during pause");

        thread.resume();
        assertEventuallyTrue(
                () -> {
                    final int fifthValueRead = count.get();
                    return fifthValueRead > fourthValueRead;
                },
                Duration.ofSeconds(1),
                "expected count to have been increased");

        thread.stop();
    }

    /**
     * Tests a bug that used to exist where the thread would not pause if LogAfterPauseDuration was set to ZERO
     */
    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Pause Test where LogAfterPause is set to 0")
    void zeroLogPauseTest() throws InterruptedException {

        final AtomicInteger count = new AtomicInteger(0);

        final StoppableThread thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(() -> {
                    Thread.sleep(5);
                    count.incrementAndGet();
                })
                .setLogAfterPauseDuration(Duration.ZERO)
                .build();

        thread.start();

        // let it start working
        Thread.sleep(10);

        // try a few times since it doesn't always recreate the exact conditions
        for (int i = 0; i < 3; i++) {
            thread.pause();
            final int beforeSleep = count.get();
            Thread.sleep(15);
            final int afterSleep = count.get();
            assertEquals(beforeSleep, afterSleep, "expected count to not change during pause");
            thread.resume();
        }

        thread.stop();
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Hanging Thread Test")
    void hangingThreadTest() throws InterruptedException {

        final AtomicBoolean finish = new AtomicBoolean(false);

        final StoppableThread thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(() -> {
                    long value = 0;
                    while (!finish.get()) {
                        value++;
                    }
                })
                .setHangingThreadPeriod(Duration.ofSeconds(1))
                .build();

        thread.start();

        final Thread stoppingThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(thread::stop)
                .build();
        stoppingThread.start();

        Thread.sleep(100);

        assertTrue(thread.isAlive(), "thread should have not yet died");
        assertTrue(stoppingThread.isAlive(), "stopping thread should have not yet finished");
        assertFalse(thread.isHanging(), "it is too early for this thread to be considered a hanging thread");

        Thread.sleep(1000);

        assertTrue(thread.isAlive(), "thread should have not yet died");
        assertTrue(stoppingThread.isAlive(), "stopping thread should have not yet finished");
        assertTrue(thread.isHanging(), "thread should now be considered a hanging thread");

        finish.set(true);
        Thread.sleep(100);

        assertFalse(thread.isAlive(), "thread should be dead");
        assertFalse(thread.isHanging(), "once a thread dies it should no longer be a hanging thread");

        thread.stop();
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Hanging Thread Disabled Test")
    void hangingThreadDisabledTest() throws InterruptedException {
        final AtomicBoolean finish = new AtomicBoolean(false);

        final StoppableThread thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(() -> {
                    long value = 0;
                    while (!finish.get()) {
                        value++;
                    }
                })
                .setHangingThreadPeriod(Duration.ofSeconds(0))
                .build();

        thread.start();

        final Thread stoppingThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(thread::stop)
                .build();
        stoppingThread.start();

        Thread.sleep(100);

        assertTrue(thread.isAlive(), "thread should have not yet died");
        assertTrue(stoppingThread.isAlive(), "stopping thread should have not yet finished");
        assertFalse(thread.isHanging(), "hanging thread detection is disabled");

        Thread.sleep(1000);

        assertTrue(thread.isAlive(), "thread should have not yet died");
        assertTrue(stoppingThread.isAlive(), "stopping thread should have not yet finished");
        assertFalse(thread.isHanging(), "hanging thread detection is disabled");

        finish.set(true);
        Thread.sleep(100);

        assertFalse(thread.isAlive(), "thread should be dead");
        assertFalse(thread.isHanging(), "hanging thread detection is disabled");

        thread.stop();
    }

    @Test
    @DisplayName("Rate Configuration Test")
    void rateConfigurationTest() {
        final StoppableThreadConfiguration<?> configuration =
                new StoppableThreadConfiguration<>(getStaticThreadManager());

        assertNull(configuration.getMinimumPeriod(), "should be null until set");
        assertEquals(-1, configuration.getMaximumRate(), "should have sane default value");

        configuration.setMinimumPeriod(Duration.ofSeconds(2));
        assertEquals(0.5, configuration.getMaximumRate(), "rate should be properly set");

        configuration.setMinimumPeriod(Duration.ofMillis(50));
        assertEquals(20, configuration.getMaximumRate(), "rate should be properly set");

        configuration.setMaximumRate(10);
        assertEquals(Duration.ofMillis(100), configuration.getMinimumPeriod(), "period should be properly set");

        configuration.setMaximumRate(0.1);
        assertEquals(Duration.ofSeconds(10), configuration.getMinimumPeriod(), "period should be properly set");
    }

    @Test
    @DisplayName("Max Rate Test")
    void maxRateTest() throws InterruptedException {
        final AtomicInteger counter = new AtomicInteger(0);
        final InterruptableRunnable work = counter::getAndIncrement;

        final StoppableThread thread0 = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setMaximumRate(5)
                .setWork(work)
                .build(true);

        SECONDS.sleep(1);
        thread0.stop();
        assertTrue(
                counter.get() >= 3 && counter.get() <= 7,
                "counter should have value close to 5, has " + counter.get() + " instead");

        counter.set(0);
        final StoppableThread thread1 = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setMaximumRate(100)
                .setWork(work)
                .build(true);

        SECONDS.sleep(1);
        thread1.stop();
        assertTrue(
                counter.get() > 90 && counter.get() < 110,
                "counter should have value close to 100, has " + counter.get() + " instead");

        counter.set(0);
        final StoppableThread thread2 = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setMaximumRate(500)
                .setWork(work)
                .build(true);

        SECONDS.sleep(1);
        thread2.stop();
        assertTrue(
                counter.get() > 400 && counter.get() < 550,
                "counter should have value close to 500, has " + counter.get() + " instead");
    }

    @Test
    @DisplayName("Seed Test")
    void seedTest() throws InterruptedException {
        final AtomicLong count = new AtomicLong();
        final AtomicBoolean enableLongSleep = new AtomicBoolean();
        final CountDownLatch longSleepStarted = new CountDownLatch(1);

        final StoppableThread stoppableThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setThreadName("stoppable-thread")
                .setWork(() -> {
                    count.getAndIncrement();
                    if (enableLongSleep.get()) {
                        longSleepStarted.countDown();
                        SECONDS.sleep(999999999);
                    }
                })
                .build();

        final ThreadSeed seed = stoppableThread.buildSeed();

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
                () -> thread.getName().equals("<stoppable-thread>"),
                Duration.ofSeconds(1),
                "stoppable thread should eventually take over");

        assertEventuallyTrue(
                () -> count.get() > 1_000, Duration.ofSeconds(1), "count should have increased more by now");

        // Cause the thread to enter a very long sleep
        enableLongSleep.set(true);
        longSleepStarted.await();

        // Stop the thread, will cause it to be interrupted. Control will return to original thread.
        stoppableThread.stop();

        assertEventuallyTrue(seedHasYieldedControl::get, Duration.ofSeconds(1), "seed should have yielded");
        assertEquals("<inject-into-this-thread>", thread.getName(), "original settings should have been restored");

        exitLatch.countDown();
    }

    @Test
    @DisplayName("Configuration Mutability Test")
    void configurationMutabilityTest() {
        // Build should make the configuration immutable
        final StoppableThreadConfiguration<?> configuration =
                new StoppableThreadConfiguration<>(getStaticThreadManager()).setWork(() -> {});

        assertTrue(configuration.isMutable(), "configuration should be mutable");

        configuration.build();
        assertTrue(configuration.isImmutable(), "configuration should be immutable");

        assertThrows(
                MutabilityException.class,
                () -> configuration.setNodeId(NodeId.of(0L)),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setComponent("asdf"),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setThreadName("asdf"),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setFullyFormattedThreadName("asdf"),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setOtherNodeId(NodeId.of(0L)),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setThreadGroup(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration.setDaemon(false), "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setPriority(Thread.MAX_PRIORITY),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setContextClassLoader(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setExceptionHandler(null),
                "configuration should be immutable");
        assertThrows(MutabilityException.class, () -> configuration.setWork(null), "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setStopBehavior(Stoppable.StopBehavior.BLOCKING),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class, () -> configuration.setJoinWaitMs(100), "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setFinalCycleWork(null),
                "configuration should be immutable");
        assertThrows(
                MutabilityException.class,
                () -> configuration.setHangingThreadPeriod(Duration.ofSeconds(1)),
                "configuration should be immutable");
    }

    @Test
    @DisplayName("Single Use Per Config Test")
    void singleUsePerConfigTest() {

        // build() should cause future calls to build() to fail, and start() should cause buildSeed() to fail.
        final StoppableThreadConfiguration<?> configuration0 = new StoppableThreadConfiguration<>(
                        getStaticThreadManager())
                .setWork(() -> {
                    MILLISECONDS.sleep(1);
                });

        final StoppableThread stoppableThread0 = configuration0.build();

        assertThrows(MutabilityException.class, configuration0::build, "configuration has already been used");

        stoppableThread0.start();

        assertThrows(IllegalStateException.class, stoppableThread0::buildSeed, "configuration has already been used");

        stoppableThread0.stop();

        // buildSeed() should cause future calls to buildSeed() and start() to fail.
        final StoppableThreadConfiguration<?> configuration1 = new StoppableThreadConfiguration<>(
                        getStaticThreadManager())
                .setWork(() -> {
                    MILLISECONDS.sleep(1);
                });

        final StoppableThread stoppableThread1 = configuration1.build();
        stoppableThread1.buildSeed();

        assertThrows(IllegalStateException.class, stoppableThread1::buildSeed, "configuration has already been used");
        assertThrows(IllegalStateException.class, stoppableThread1::start, "configuration has already been used");
    }

    @Test
    @DisplayName("Pause Then Stop Test")
    void pauseThenStopTest() throws InterruptedException {
        final AtomicInteger count = new AtomicInteger();

        final StoppableThread thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(() -> {
                    count.getAndIncrement();
                    MILLISECONDS.sleep(1);
                })
                .build(true);

        assertEventuallyTrue(() -> count.get() > 0, Duration.ofSeconds(1), "thread should have started by now");

        thread.pause();

        final int count0 = count.get();

        // Thread should be paused, but give it enough time to misbehave if it is going to misbehave
        MILLISECONDS.sleep(100);

        final int count1 = count.get();

        assertEquals(count0, count1, "thread should be paused");

        thread.stop();
        thread.join();
        assertFalse(thread.isAlive(), "thread should cleanly die");
    }

    @Test
    @DisplayName("Join Before Start Test")
    void joinBeforeStartTest() throws InterruptedException {
        final StoppableThread stoppableThread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(() -> HOURS.sleep(10000000))
                .build();

        final Thread joinThread = new ThreadConfiguration(getStaticThreadManager())
                .setInterruptableRunnable(stoppableThread::join)
                .build(true);

        // Give the joining thread plenty of time to become blocked.
        MILLISECONDS.sleep(20);
        assertTrue(joinThread.isAlive(), "thread should still be blocked");

        stoppableThread.start();
        stoppableThread.stop();

        assertEventuallyFalse(joinThread::isAlive, Duration.ofSeconds(1), "thread should have died");
    }

    @Test
    @DisplayName("Join Before Start Seed Test")
    void joinBeforeStartSeedTest() throws InterruptedException {
        final StoppableThread stoppableThread1 = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setWork(() -> HOURS.sleep(10000000))
                .build();

        final Thread joinThread1 = new ThreadConfiguration(getStaticThreadManager())
                .setInterruptableRunnable(stoppableThread1::join)
                .build(true);

        // Give the joining thread plenty of time to become blocked.
        MILLISECONDS.sleep(20);
        assertTrue(joinThread1.isAlive(), "thread should still be blocked");

        final ThreadSeed seed1 = stoppableThread1.buildSeed();

        final Thread seedThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(seed1::inject)
                .build(true);

        // Give the seed some time to start
        MILLISECONDS.sleep(20);

        stoppableThread1.stop();
        stoppableThread1.join();

        assertEventuallyFalse(joinThread1::isAlive, Duration.ofSeconds(1), "thread should have died");
    }

    @Test
    @DisplayName("Copy Test")
    void copyTest() {
        final InterruptableRunnable work = () -> {};

        final InterruptableRunnable finalCycleWork = () -> {};

        final StoppableThreadConfiguration<?> configuration = new StoppableThreadConfiguration<>(
                        getStaticThreadManager())
                .setStopBehavior(Stoppable.StopBehavior.BLOCKING)
                .setJoinWaitMs(1234)
                .setWork(work)
                .setFinalCycleWork(finalCycleWork)
                .setHangingThreadPeriod(Duration.ofMillis(1234));

        final StoppableThreadConfiguration<?> copy1 = configuration.copy();

        assertEquals(configuration.getStopBehavior(), copy1.getStopBehavior(), "copy configuration should match");
        assertEquals(configuration.getJoinWaitMs(), copy1.getJoinWaitMs(), "copy configuration should match");
        assertSame(configuration.getWork(), copy1.getWork(), "copy configuration should match");
        assertSame(configuration.getFinalCycleWork(), copy1.getFinalCycleWork(), "copy configuration should match");
        assertEquals(
                configuration.getHangingThreadPeriod(),
                copy1.getHangingThreadPeriod(),
                "copy configuration should match");

        // It shouldn't matter if the original is immutable.
        configuration.build();

        final StoppableThreadConfiguration<?> copy2 = configuration.copy();
        assertTrue(copy2.isMutable(), "copy should be mutable");

        assertEquals(configuration.getStopBehavior(), copy2.getStopBehavior(), "copy configuration should match");
        assertEquals(configuration.getJoinWaitMs(), copy2.getJoinWaitMs(), "copy configuration should match");
        assertSame(configuration.getWork(), copy2.getWork(), "copy configuration should match");
        assertSame(configuration.getFinalCycleWork(), copy2.getFinalCycleWork(), "copy configuration should match");
        assertEquals(
                configuration.getHangingThreadPeriod(),
                copy2.getHangingThreadPeriod(),
                "copy configuration should match");
    }

    @Test
    @DisplayName("Interrupt Flag Test")
    void interruptFlagTest() throws InterruptedException {
        final StoppableThread stoppableThread = new StoppableThreadConfiguration(getStaticThreadManager())
                .setWork(() -> {
                    try {
                        HOURS.sleep(10000000);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build(true);

        assertEventuallyTrue(stoppableThread::isAlive, Duration.ofSeconds(1), "thread should be alive by now");

        stoppableThread.interrupt();

        stoppableThread.join(1000);
        assertFalse(stoppableThread.isAlive(), "thread should have died by now");
    }
}
