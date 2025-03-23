// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import static com.swirlds.common.test.fixtures.AssertionUtils.completeBeforeTimeout;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.consensus.model.utility.interrupt.Uninterruptable.abortAndThrowIfInterrupted;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.notification.internal.AsyncNotificationEngine;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.futures.ConcurrentFuturePool;
import com.swirlds.common.threading.futures.FuturePool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hiero.consensus.model.utility.StandardFuture;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Integer Notification Tests")
public class IntegerNotificationTests {

    private static final boolean ENABLE_DIAG_PRINTOUT = false;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Sync: Unordered Summation")
    public void syncUnorderedSummation(int iterations) {

        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicLong lastKnownId = new AtomicLong(0);

        assertNotNull(engine);

        engine.register(SyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        for (int i = 0; i < iterations; i++) {
            engine.dispatch(SyncUnorderedIntegerListener.class, new IntegerNotification(1));
        }

        assertEquals(iterations, lastKnownId.get());
        assertEquals(iterations, sum.get());

        engine.shutdown();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Sync: Unordered Dual Ops")
    public void syncUnorderedDualOps(int iterations) {

        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicInteger subtract = new AtomicInteger(iterations * 4);
        final AtomicLong lastKnownId = new AtomicLong(0);

        assertNotNull(engine);

        engine.register(SyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        engine.register(SyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertEquals(lastKnownId.get(), n.getSequence());

            subtract.addAndGet(-1 * n.getValue());
            lastKnownId.set(n.getSequence());
        });

        for (int i = 0; i < iterations; i++) {
            engine.dispatch(SyncUnorderedIntegerListener.class, new IntegerNotification(4));
        }

        assertEquals(iterations * 4, sum.get());
        assertEquals(0, subtract.get());

        engine.shutdown();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Async: Unordered Summation")
    public void asyncUnorderedSummation(int iterations) {

        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicLong lastKnownId = new AtomicLong(0);

        assertNotNull(engine);

        engine.register(AsyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        final FuturePool<NotificationResult<IntegerNotification>> futures = new FuturePool<>();

        for (int i = 0; i < iterations; i++) {
            futures.add(engine.dispatch(AsyncUnorderedIntegerListener.class, new IntegerNotification(1)));
        }

        futures.waitForCompletion();
        engine.shutdown();

        assertEquals(iterations, lastKnownId.get());
        assertEquals(iterations, sum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Async: Unordered Dual Ops")
    public void asyncUnorderedDualOps(int iterations) {

        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicInteger subtract = new AtomicInteger(iterations * 4);
        final AtomicLong lastKnownId = new AtomicLong(0);

        assertNotNull(engine);

        engine.register(AsyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        engine.register(AsyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertEquals(lastKnownId.get(), n.getSequence());

            subtract.addAndGet(-1 * n.getValue());
            lastKnownId.set(n.getSequence());
        });

        final FuturePool<NotificationResult<IntegerNotification>> futures = new FuturePool<>();

        for (int i = 0; i < iterations; i++) {
            futures.add(engine.dispatch(AsyncUnorderedIntegerListener.class, new IntegerNotification(4)));
        }

        futures.waitForCompletion();
        engine.shutdown();

        assertEquals(iterations * 4, sum.get());
        assertEquals(0, subtract.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Sync: Unordered MT Summation")
    public void syncUnorderedThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicLong lastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(SyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertNotEquals(lastKnownId.get(), n.getSequence());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            final Future future = executorService.submit(() -> {
                try {
                    engine.dispatch(SyncUnorderedIntegerListener.class, new IntegerNotification(1));
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(5, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 5 second limit");

        engine.shutdown();

        assertEquals(iterations, sum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Sync: Ordered MT Summation")
    public void syncOrderedThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicLong lastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(SyncOrderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            final Future future = executorService.submit(() -> {
                try {
                    engine.dispatch(SyncOrderedIntegerListener.class, new IntegerNotification(1));
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(5, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 5 second limit");

        engine.shutdown();

        assertEquals(iterations, lastKnownId.get());
        assertEquals(iterations, sum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Async: Unordered MT Summation")
    public void asyncUnorderedThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicLong lastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(AsyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertNotEquals(lastKnownId.get(), n.getSequence());
            //			assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<NotificationResult<IntegerNotification>> futures = new ConcurrentFuturePool<>();
        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            Future future = executorService.submit(() -> {
                try {
                    futures.add(engine.dispatch(AsyncUnorderedIntegerListener.class, new IntegerNotification(1)));
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();
        futures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(5, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 5 second limit");

        engine.shutdown();

        assertEquals(iterations, sum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Async: Ordered MT Summation")
    public void asyncOrderedThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger sum = new AtomicInteger(0);
        final AtomicLong lastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(AsyncOrderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Notification #%06d", n.getSequence()));
            }

            assertTrue(n.getSequence() > lastKnownId.get());

            sum.addAndGet(n.getValue());
            lastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<NotificationResult<IntegerNotification>> futures = new ConcurrentFuturePool<>();
        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            final Future future = executorService.submit(() -> {
                try {
                    futures.add(engine.dispatch(AsyncOrderedIntegerListener.class, new IntegerNotification(1)));
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();
        futures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(5, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 5 second limit");

        engine.shutdown();

        assertEquals(iterations, lastKnownId.get());
        assertEquals(iterations, sum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Mixed: Unordered MT Summation")
    public void mixedUnorderedThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger syncSum = new AtomicInteger(0);
        final AtomicInteger asyncSum = new AtomicInteger(0);
        final AtomicLong syncLastKnownId = new AtomicLong(0);
        final AtomicLong asyncLastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(SyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Sync Notification #%06d", n.getSequence()));
            }

            assertNotEquals(syncLastKnownId.get(), n.getSequence());
            //			assertTrue(n.getSequence() > syncLastKnownId.get());

            syncSum.addAndGet(n.getValue());
            syncLastKnownId.set(n.getSequence());
        });

        engine.register(AsyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing ASYNC Notification #%06d", n.getSequence()));
            }

            assertNotEquals(asyncLastKnownId.get(), n.getSequence());
            //			assertTrue(n.getSequence() > asyncLastKnownId.get());

            asyncSum.addAndGet(n.getValue());
            asyncLastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<NotificationResult<IntegerNotification>> futures = new ConcurrentFuturePool<>();
        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            final int iter = i;
            final Future future = executorService.submit(() -> {
                try {
                    if (isEven(iter)) {
                        futures.add(engine.dispatch(SyncUnorderedIntegerListener.class, new IntegerNotification(1)));
                    } else {
                        futures.add(engine.dispatch(AsyncUnorderedIntegerListener.class, new IntegerNotification(1)));
                    }
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();
        futures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(5, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 5 second limit");

        engine.shutdown();

        final int expectedSyncIterations = (iterations / 2) + ((isOdd(iterations)) ? 1 : 0);
        final int expectedAsyncIterations = (iterations / 2);

        assertEquals(expectedSyncIterations, syncSum.get());
        assertEquals(expectedAsyncIterations, asyncSum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("Mixed: Ordered MT Summation")
    public void mixedOrderedThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger syncSum = new AtomicInteger(0);
        final AtomicInteger asyncSum = new AtomicInteger(0);
        final AtomicLong syncLastKnownId = new AtomicLong(0);
        final AtomicLong asyncLastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(SyncOrderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Sync Notification #%06d", n.getSequence()));
            }

            //			assertNotEquals(syncLastKnownId.get(), n.getSequence());
            assertTrue(n.getSequence() > syncLastKnownId.get());

            syncSum.addAndGet(n.getValue());
            syncLastKnownId.set(n.getSequence());
        });

        engine.register(AsyncOrderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing ASYNC Notification #%06d", n.getSequence()));
            }

            //			assertNotEquals(asyncLastKnownId.get(), n.getSequence());
            assertTrue(n.getSequence() > asyncLastKnownId.get());

            asyncSum.addAndGet(n.getValue());
            asyncLastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<NotificationResult<IntegerNotification>> futures = new ConcurrentFuturePool<>();
        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            final int iter = i;
            final Future future = executorService.submit(() -> {
                try {
                    if (isEven(iter)) {
                        futures.add(engine.dispatch(SyncOrderedIntegerListener.class, new IntegerNotification(1)));
                    } else {
                        futures.add(engine.dispatch(AsyncOrderedIntegerListener.class, new IntegerNotification(1)));
                    }
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();
        futures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(5, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 5 second limit");

        engine.shutdown();

        final int expectedSyncIterations = (iterations / 2) + ((isOdd(iterations)) ? 1 : 0);
        final int expectedAsyncIterations = (iterations / 2);

        assertTrue(
                ((syncLastKnownId.get() == iterations && asyncLastKnownId.get() <= (iterations - 1))
                        || (syncLastKnownId.get() <= (iterations - 1) && asyncLastKnownId.get() == iterations)),
                String.format(
                        "Last Known Sequences - Out of Range [ sync = %d, async = %d ]",
                        syncLastKnownId.get(), asyncLastKnownId.get()));

        assertEquals(expectedSyncIterations, syncSum.get());
        assertEquals(expectedAsyncIterations, asyncSum.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5, 21, 57, 1_000, 10_000, 100_000})
    @DisplayName("SUAO: MT Summation")
    @Disabled("this test is flaky")
    public void suaoThreadedSummation(int iterations) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final AtomicInteger syncSum = new AtomicInteger(0);
        final AtomicInteger asyncSum = new AtomicInteger(0);
        final AtomicLong syncLastKnownId = new AtomicLong(0);
        final AtomicLong asyncLastKnownId = new AtomicLong(0);
        final ExecutorService executorService =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        assertNotNull(engine);

        engine.register(SyncUnorderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing Sync Notification #%06d", n.getSequence()));
            }

            assertNotEquals(syncLastKnownId.get(), n.getSequence());
            //			assertTrue(n.getSequence() > syncLastKnownId.get());

            syncSum.addAndGet(n.getValue());

            if (n.getSequence() > syncLastKnownId.get()) {
                syncLastKnownId.set(n.getSequence());
            }
        });

        engine.register(AsyncOrderedIntegerListener.class, (n) -> {
            if (ENABLE_DIAG_PRINTOUT) {
                System.out.println(String.format("Processing ASYNC Notification #%06d", n.getSequence()));
            }

            //			assertNotEquals(asyncLastKnownId.get(), n.getSequence());
            assertTrue(n.getSequence() > asyncLastKnownId.get());

            asyncSum.addAndGet(n.getValue());
            asyncLastKnownId.set(n.getSequence());
        });

        final ConcurrentFuturePool<NotificationResult<IntegerNotification>> futures = new ConcurrentFuturePool<>();
        final ConcurrentFuturePool<?> callableFutures = new ConcurrentFuturePool<>();

        for (int i = 0; i < iterations; i++) {
            final int iter = i;
            final Future future = executorService.submit(() -> {
                try {
                    if (isEven(iter)) {
                        futures.add(engine.dispatch(SyncUnorderedIntegerListener.class, new IntegerNotification(1)));
                    } else {
                        futures.add(engine.dispatch(AsyncOrderedIntegerListener.class, new IntegerNotification(1)));
                    }
                } catch (DispatchException ex) {
                    ex.printStackTrace();
                }
            });

            callableFutures.add(future);
        }

        callableFutures.waitForCompletion();
        futures.waitForCompletion();

        executorService.shutdown();
        assertTrue(
                executorService.awaitTermination(10, TimeUnit.SECONDS),
                "ExecutorService failed to stop within the 10 second limit");

        engine.shutdown();

        final int expectedSyncIterations = (iterations / 2) + ((isOdd(iterations)) ? 1 : 0);
        final int expectedAsyncIterations = (iterations / 2);

        assertTrue(
                ((syncLastKnownId.get() == iterations && asyncLastKnownId.get() <= (iterations - 1))
                        || (syncLastKnownId.get() <= (iterations - 1) && asyncLastKnownId.get() == iterations)),
                String.format(
                        "Last Known Sequences - Out of Range [ sync = %d, async = %d ]",
                        syncLastKnownId.get(), asyncLastKnownId.get()));

        assertEquals(expectedSyncIterations, syncSum.get(), "Unexpected Sync Summation");
        assertEquals(expectedAsyncIterations, asyncSum.get(), "Unexpected ASYNC Summation");
    }

    private static boolean isEven(final int num) {
        return num % 2 == 0;
    }

    private static boolean isOdd(final int num) {
        return num % 2 != 0;
    }

    /**
     * Configuration for completionCallbackTest.
     *
     * @param name
     * 		a string describing the test
     * @param listenerFactory
     * 		builds a listener class
     * @param useCallback
     * 		if true then a completion callback should be registered
     */
    private record CompletionCallbackTestConfiguration(
            String name,
            Function<Consumer<IntegerNotification>, Listener<IntegerNotification>> listenerFactory,
            boolean useCallback) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Build arguments for {@link #completionTest(CompletionCallbackTestConfiguration)}.
     */
    protected static Stream<Arguments> buildArgumentsForCompletionCallbackTest() {
        final List<Arguments> arguments = new ArrayList<>();

        for (final boolean useCallback : List.of(true, false)) {
            arguments.add(Arguments.of(new CompletionCallbackTestConfiguration(
                    "async unordered, callback = " + useCallback, TestListenerAsyncUnordered::new, useCallback)));

            arguments.add(Arguments.of(new CompletionCallbackTestConfiguration(
                    "async ordered, callback = " + useCallback, TestListenerAsyncOrdered::new, useCallback)));

            arguments.add(Arguments.of(new CompletionCallbackTestConfiguration(
                    "sync unordered, callback = " + useCallback, TestListenerSyncUnordered::new, useCallback)));

            arguments.add(Arguments.of(new CompletionCallbackTestConfiguration(
                    "sync ordered, callback = " + useCallback, TestListenerSyncOrdered::new, useCallback)));
        }

        return arguments.stream();
    }

    /**
     * A utility listener class. Passes the notification to a callback method.
     */
    @DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.UNORDERED)
    private static class TestListenerAsyncUnordered implements Listener<IntegerNotification> {
        private final Consumer<IntegerNotification> callback;

        public TestListenerAsyncUnordered(Consumer<IntegerNotification> callback) {
            this.callback = callback;
        }

        @Override
        public void notify(final IntegerNotification data) {
            callback.accept(data);
        }
    }

    /**
     * A utility listener class. Passes the notification to a callback method.
     */
    @DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.ORDERED)
    private static class TestListenerAsyncOrdered implements Listener<IntegerNotification> {
        private final Consumer<IntegerNotification> callback;

        public TestListenerAsyncOrdered(Consumer<IntegerNotification> callback) {
            this.callback = callback;
        }

        @Override
        public void notify(final IntegerNotification data) {
            callback.accept(data);
        }
    }

    /**
     * A utility listener class. Passes the notification to a callback method.
     */
    @DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.UNORDERED)
    private static class TestListenerSyncUnordered implements Listener<IntegerNotification> {
        private final Consumer<IntegerNotification> callback;

        public TestListenerSyncUnordered(Consumer<IntegerNotification> callback) {
            this.callback = callback;
        }

        @Override
        public void notify(final IntegerNotification data) {
            callback.accept(data);
        }
    }

    /**
     * A utility listener class. Passes the notification to a callback method.
     */
    @DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
    private static class TestListenerSyncOrdered implements Listener<IntegerNotification> {
        private final Consumer<IntegerNotification> callback;

        public TestListenerSyncOrdered(Consumer<IntegerNotification> callback) {
            this.callback = callback;
        }

        @Override
        public void notify(final IntegerNotification data) {
            callback.accept(data);
        }
    }

    /**
     * This test ensures that the {@link Future} returned by the dispatch method is invoked strictly after all
     * notifications have been delivered. It also exercises the completion callback that can be optionally
     * triggered when all notifications are delivered.
     *
     * @param config
     * 		configuration for this test
     */
    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @MethodSource("buildArgumentsForCompletionCallbackTest")
    @DisplayName("Completion Test")
    void completionTest(final CompletionCallbackTestConfiguration config) throws InterruptedException {
        final NotificationEngine engine = new AsyncNotificationEngine(getStaticThreadManager());

        final Class<? extends Listener<IntegerNotification>> listenerClass =
                (Class<? extends Listener<IntegerNotification>>)
                        config.listenerFactory.apply(null).getClass();

        final int listenerCount = 8;
        final int value = 1234;

        // Each latch will block the completion of a listener
        final List<CountDownLatch> latchList = new LinkedList<>();

        // Each of these atomic booleans will be set to true when its corresponding listener is finished
        final List<AtomicBoolean> completionList = new LinkedList<>();

        // If this run is configured to use a callback then create a callback that should be invoked
        // only after all listeners have completed
        final StandardFuture.CompletionCallback<NotificationResult<IntegerNotification>> callback;
        final AtomicReference<NotificationResult<IntegerNotification>> callbackNotificationResult =
                new AtomicReference<>();
        if (config.useCallback) {
            callback = notification -> {
                assertNotNull(notification, "notification should not be null");
                assertNull(callbackNotificationResult.get(), "should not be set twice");
                callbackNotificationResult.set(notification);
            };
        } else {
            callback = null;
        }

        // Register a bunch of listeners. Each listener will block until a latch is completed, then will
        // mark completion by writing to an atomic boolean. The first listener will throw an exception.
        for (int i = 0; i < listenerCount; i++) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean completed = new AtomicBoolean();
            completionList.add(completed);
            latchList.add(latch);
            completionList.add(completed);
            final boolean isFirstListener = i == 0;
            final Listener<IntegerNotification> listener = config.listenerFactory.apply(notification -> {
                abortAndThrowIfInterrupted(latch::await, "test thread thread interrupted");
                assertFalse(completed.get(), "should only be completed once");
                assertEquals(value, notification.getValue(), "unexpected value");
                completed.set(true);
                if (isFirstListener) {
                    throw new RuntimeException("this is intentionally thrown");
                }
            });
            engine.register((Class<Listener<IntegerNotification>>) listener.getClass(), listener);
        }

        // Send the notification on a background thread. This thread will block for a while, and will
        // allow the main thread to do assertions while those operations are blocking.
        final AtomicReference<NotificationResult<IntegerNotification>> futureNotificationResult =
                new AtomicReference<>();
        final Thread dispatchThread = new ThreadConfiguration(getStaticThreadManager())
                .setInterruptableRunnable(() -> {
                    final Future<NotificationResult<IntegerNotification>> future =
                            engine.dispatch(listenerClass, new IntegerNotification(value), callback);

                    final NotificationResult<IntegerNotification> result =
                            assertDoesNotThrow(() -> future.get(), "this should never throw");
                    assertEquals(1, result.getFailureCount(), "exactly one failure expected");
                    futureNotificationResult.set(result);
                })
                .build(true);

        // Release latches one at a time, allowing notifications to complete. We should not see the future complete
        // or the completion callback invoked until all are released.
        for (final CountDownLatch latch : latchList) {
            // Nothing should happen during this sleep,
            // but give the background threads time to misbehave if they want to
            MILLISECONDS.sleep(10);

            assertNull(futureNotificationResult.get(), "future should not have completed yet");
            assertNull(callbackNotificationResult.get(), "callback should not have been called yet");

            latch.countDown();
        }

        // At this point in time, all notifications should have been completed, or should soon be completed.
        completeBeforeTimeout(
                () -> {
                    for (final AtomicBoolean completed : completionList) {
                        assertTrue(completed.get(), "notification should have completed");
                    }

                    assertNotNull(futureNotificationResult.get(), "notification should have completed");
                    assertEquals(1, futureNotificationResult.get().getFailureCount(), "exactly one failure expected");

                    if (config.useCallback) {
                        assertSame(
                                futureNotificationResult.get(),
                                callbackNotificationResult.get(),
                                "callback should have completed with the same result");
                    }
                },
                Duration.ofSeconds(1),
                "callbacks and futures should be completed by now");

        engine.shutdown();
    }
}
