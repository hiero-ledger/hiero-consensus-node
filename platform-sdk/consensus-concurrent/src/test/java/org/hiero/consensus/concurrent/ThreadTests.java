// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent;

import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.hiero.consensus.concurrent.framework.config.ThreadConfiguration.captureThreadConfiguration;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.state.MutabilityException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.framework.ThreadSeed;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Thread Tests")
class ThreadTests {

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Default Configuration Test")
    void defaultConfigurationTest() throws InterruptedException {

        final AtomicBoolean runnableCalled = new AtomicBoolean(false);
        final Runnable runnable = () -> {
            assertFalse(runnableCalled.get(), "runnable should only be called once");
            runnableCalled.set(true);
        };

        final ThreadConfiguration config = new ThreadConfiguration(getStaticThreadManager());
        final Thread thread = config.setRunnable(runnable).build();

        assertSame(
                Thread.currentThread().getThreadGroup(),
                thread.getThreadGroup(),
                "thread group should match current thread group");

        assertTrue(thread.isDaemon(), "by default threads should be daemons");

        assertEquals(Thread.NORM_PRIORITY, thread.getPriority(), "by default normal priority should be used");

        assertSame(
                Thread.currentThread().getContextClassLoader(),
                thread.getContextClassLoader(),
                "class loader should be same as current thread");

        assertFalse(thread.isAlive(), "thread should not yet have started");
        assertFalse(runnableCalled.get(), "runnable should not yet have been called");

        thread.start();
        thread.join();

        assertTrue(runnableCalled.get(), "runnable should have been called");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Thread Group Test")
    void threadGroupTest() throws InterruptedException {

        final ThreadGroup group1 = Thread.currentThread().getThreadGroup();
        final Runnable runnable1 =
                () -> assertSame(group1, Thread.currentThread().getThreadGroup(), "expected thread group to match");

        final ThreadGroup group2 = new ThreadGroup("myGroup");
        final Runnable runnable2 =
                () -> assertSame(group2, Thread.currentThread().getThreadGroup(), "expected thread group to match");

        final AtomicBoolean threadException = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "there should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setThreadGroup(group2)
                .setRunnable(runnable2)
                .build(true)
                .join();
        assertFalse(threadException.get(), "there should not have been any exceptions");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Daemon Test")
    void daemonTest() throws InterruptedException {
        final Runnable runnable1 =
                () -> assertTrue(Thread.currentThread().isDaemon(), "expected thread to be a daemon");

        final Runnable runnable2 =
                () -> assertFalse(Thread.currentThread().isDaemon(), "expected thread to not be a daemon");

        final AtomicBoolean threadException = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "there should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setDaemon(true)
                .setRunnable(runnable1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "there should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setDaemon(false)
                .setRunnable(runnable2)
                .build(true)
                .join();
        assertFalse(threadException.get(), "there should not have been any exceptions");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Class Loader Test")
    void classLoaderTest() throws InterruptedException {

        final ClassLoader loader1 = Thread.currentThread().getContextClassLoader();
        final Runnable runnable1 = () ->
                assertSame(loader1, Thread.currentThread().getContextClassLoader(), "expected class loader to match");

        final ClassLoader loader2 =
                Thread.currentThread().getContextClassLoader().getParent();
        final Runnable runnable2 = () ->
                assertSame(loader2, Thread.currentThread().getContextClassLoader(), "expected class loader to match");

        final AtomicBoolean threadException = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable2)
                .setContextClassLoader(loader2)
                .build(true)
                .join();
        assertFalse(threadException.get(), "should not have been any exceptions");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Priority Test")
    void priorityTest() throws InterruptedException {
        final int priority1 = Thread.NORM_PRIORITY;
        final Runnable runnable1 =
                () -> assertEquals(priority1, Thread.currentThread().getPriority(), "expected priority to match");

        final int priority2 = Thread.NORM_PRIORITY + 1;
        final Runnable runnable2 =
                () -> assertEquals(priority2, Thread.currentThread().getPriority(), "expected priority to match");

        final AtomicBoolean threadException = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable2)
                .setPriority(priority2)
                .build(true)
                .join();
        assertFalse(threadException.get(), "should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> {
                    e.printStackTrace();
                    threadException.set(true);
                })
                .setRunnable(runnable1)
                .setPriority(priority1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "should not have been any exceptions");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Exception Handler Test")
    void exceptionHandlerTest() throws InterruptedException {
        final Runnable runnable1 = () -> {};
        final Runnable runnable2 = () -> {
            throw new RuntimeException("!");
        };

        final AtomicBoolean threadException = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> threadException.set(true))
                .setRunnable(runnable1)
                .build(true)
                .join();
        assertFalse(threadException.get(), "should not have been any exceptions");

        new ThreadConfiguration(getStaticThreadManager())
                .setExceptionHandler((t, e) -> threadException.set(true))
                .setRunnable(runnable2)
                .build(true)
                .join();
        assertTrue(threadException.get(), "should have been an exception");
    }

    @Test
    @DisplayName("Seed Test")
    void seedTest() {

        final ClassLoader seedLoader =
                Thread.currentThread().getContextClassLoader().getParent();

        final Thread.UncaughtExceptionHandler seedHandler = (thread, throwable) -> throwable.printStackTrace();

        final AtomicBoolean seedStarted = new AtomicBoolean();
        final CountDownLatch seedLatch = new CountDownLatch(1);

        // This seed will inject itself into another thread.
        final ThreadSeed seed = new ThreadConfiguration(getStaticThreadManager())
                .withCompositeNaming(tc -> tc.setComponent("seed-component").setThreadName("seed"))
                .setPriority(Thread.MAX_PRIORITY)
                .setContextClassLoader(seedLoader)
                .setExceptionHandler(seedHandler)
                .setInterruptableRunnable(() -> {
                    seedStarted.set(true);
                    seedLatch.await();
                })
                .buildSeed();

        final Thread.UncaughtExceptionHandler threadHandler = (thread, throwable) -> throwable.printStackTrace();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean seedHasYieldedControl = new AtomicBoolean();
        final CountDownLatch exitLatch = new CountDownLatch(1);

        // This thread will have the seed injected into it.
        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .withCompositeNaming(tc -> tc.setThreadName("inject-into-this-thread"))
                .setExceptionHandler(threadHandler)
                .setInterruptableRunnable(() -> {
                    latch.await();

                    // The seed will take over this thread for a while
                    seed.inject();

                    seedHasYieldedControl.set(true);
                    exitLatch.await();
                })
                .build(true);

        // Do some initial sanity checks on the configuration parameters of the original thread.
        final ThreadConfiguration initialConfig = captureThreadConfiguration(getStaticThreadManager(), thread);
        assertEquals("<inject-into-this-thread>", thread.getName(), "name should not have changed");
        assertSame(threadHandler, initialConfig.getExceptionHandler(), "thread should have original exception handler");
        assertEquals(Thread.NORM_PRIORITY, initialConfig.getPriority(), "thread priority should be the default");
        assertSame(
                Thread.currentThread().getContextClassLoader(),
                initialConfig.getContextClassLoader(),
                "thread should have inherited class loader");

        // Allow the seed to take over.
        latch.countDown();
        assertEventuallyTrue(seedStarted::get, Duration.ofSeconds(1), "seed should eventually start executing");

        // Validate that the thread configuration has been overwritten by the seed.
        final ThreadConfiguration overwrittenConfig = captureThreadConfiguration(getStaticThreadManager(), thread);
        assertEquals("<seed-component: seed>", thread.getName(), "name not changed to expected value");
        assertSame(seedHandler, overwrittenConfig.getExceptionHandler(), "thread should be using the seed's handler");
        assertEquals(Thread.MAX_PRIORITY, overwrittenConfig.getPriority(), "the seed should have updated the priority");
        assertSame(seedLoader, overwrittenConfig.getContextClassLoader(), "thread should have inherited class loader");

        // Allow the seed to finish and yield control back to the original thread.
        seedLatch.countDown();
        assertEventuallyTrue(seedHasYieldedControl::get, Duration.ofSeconds(1), "seed should eventually yield control");

        final ThreadConfiguration resultingConfig = captureThreadConfiguration(getStaticThreadManager(), thread);
        assertEquals("<inject-into-this-thread>", thread.getName(), "name should have been changed back");
        assertSame(
                threadHandler, resultingConfig.getExceptionHandler(), "thread should have original exception handler");
        assertEquals(Thread.NORM_PRIORITY, resultingConfig.getPriority(), "thread priority should be the default");
        assertSame(
                Thread.currentThread().getContextClassLoader(),
                resultingConfig.getContextClassLoader(),
                "thread should have original class loader");

        exitLatch.countDown();
    }

    @Test
    @DisplayName("Single Use Per Config Test")
    void singleUsePerConfigTest() {

        // build() should cause future calls to build(), buildSeed(), and buildFactory() to fail.
        final ThreadConfiguration configuration0 =
                new ThreadConfiguration(getStaticThreadManager()).setRunnable(() -> {});

        configuration0.build();

        assertThrows(MutabilityException.class, configuration0::build, "configuration has already been used");
        assertThrows(MutabilityException.class, configuration0::buildSeed, "configuration has already been used");
        assertThrows(MutabilityException.class, configuration0::buildFactory, "configuration has already been used");

        // buildSeed() should cause future calls to build(), buildSeed(), and buildFactory() to fail.
        final ThreadConfiguration configuration1 =
                new ThreadConfiguration(getStaticThreadManager()).setRunnable(() -> {});

        configuration1.buildSeed();

        assertThrows(MutabilityException.class, configuration1::build, "configuration has already been used");
        assertThrows(MutabilityException.class, configuration1::buildSeed, "configuration has already been used");
        assertThrows(MutabilityException.class, configuration1::buildFactory, "configuration has already been used");

        // buildSeed() should cause future calls to build(), buildSeed(), and buildFactory() to fail.
        final ThreadConfiguration configuration2 = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {})
                .withCompositeNaming(tc -> {});

        configuration2.buildFactory();

        assertThrows(MutabilityException.class, configuration2::build, "configuration has already been used");
        assertThrows(MutabilityException.class, configuration2::buildSeed, "configuration has already been used");
        assertThrows(MutabilityException.class, configuration2::buildFactory, "configuration has already been used");
    }
}
