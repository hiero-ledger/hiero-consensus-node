// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.futures;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.model.utility.StandardFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StandardFuture Tests")
class StandardFutureTests {

    @Test
    @DisplayName("Standard Use Test")
    void standardUseTest() throws InterruptedException {

        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        final int value = 12345;

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertEquals(value, future.get(), "unexpected value");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.complete(value);
        assertTrue(future.isDone(), "future should be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("Standard Use getAndRethrow()")
    void standardUseGetAndRethrow() throws InterruptedException {
        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");
        final int value = 12345;

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertEquals(value, future.getAndRethrow(), "unexpected value");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.complete(value);
        assertTrue(future.isDone(), "future should be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("Future Starts Out As Completed")
    void futureStartsOutAsCompleted() {

        final int value = 12345;
        final StandardFuture<Integer> future = new StandardFuture<>(value);
        assertTrue(future.isDone(), "future should be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertEquals(value, future.get(), "unexpected value");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("get() With Timeout")
    void getWithTimeout() throws InterruptedException {
        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");
        final int value = 12345;

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    // This one should time out
                    assertThrows(TimeoutException.class, () -> future.get(1, MILLISECONDS));

                    // This one should wait until the future is completed
                    try {
                        assertEquals(value, future.get(1, SECONDS), "unexpected value");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.complete(value);
        assertTrue(future.isDone(), "future should be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("getAndRethrow() With Timeout")
    void getAndRethrowWithTimeout() throws InterruptedException {
        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");
        final int value = 12345;

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    // This one should time out
                    assertThrows(TimeoutException.class, () -> future.getAndRethrow(1, MILLISECONDS));

                    // This one should wait until the future is completed
                    try {
                        assertEquals(value, future.getAndRethrow(1, SECONDS), "unexpected value");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.complete(value);
        assertTrue(future.isDone(), "future should be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("cancel() Test")
    void cancelTest() throws InterruptedException {

        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertThrows(CancellationException.class, future::get, "expected future to be cancelled");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.cancel();
        assertTrue(future.isDone(), "future should be done");
        assertTrue(future.isCancelled(), "future should be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("cancelWithError() Test")
    void cancelWithErrorTest() throws InterruptedException {

        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertThrows(ExecutionException.class, future::get, "expected future to be cancelled");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.cancelWithError(new RuntimeException());
        assertTrue(future.isDone(), "future should be done");
        assertTrue(future.isCancelled(), "future should be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("cancelWithError() getAndRethrow() Test")
    void cancelWithErrorGetAndRethrowTest() throws InterruptedException {

        final StandardFuture<Integer> future = new StandardFuture<>();
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        final AtomicBoolean finished = new AtomicBoolean(false);

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertThrows(RuntimeException.class, future::getAndRethrow, "expected future to be cancelled");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");

        future.cancelWithError(new RuntimeException());
        assertTrue(future.isDone(), "future should be done");
        assertTrue(future.isCancelled(), "future should be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("cancel() Callback Test")
    void cancelCallbackTest() throws InterruptedException {

        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean callbackFinished = new AtomicBoolean(false);

        final StandardFuture<Integer> future =
                new StandardFuture<>((final boolean interrupt, final Throwable exception) -> {
                    assertTrue(interrupt, "interrupt should be true");
                    assertTrue(exception instanceof IllegalAccessError, "exception should have correct type");
                    callbackFinished.set(true);
                });
        assertFalse(future.isDone(), "future should not be done");
        assertFalse(future.isCancelled(), "future should not be cancelled");

        new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    try {
                        assertThrows(ExecutionException.class, future::get, "expected future to be cancelled");
                    } catch (final Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    finished.set(true);
                })
                .build(true);

        assertFalse(finished.get(), "should not have finished");
        assertFalse(callbackFinished.get(), "should not have finished");
        // Sleep a little while to allow future to complete if it wants to misbehave
        MILLISECONDS.sleep(20);
        assertFalse(finished.get(), "should not have finished");
        assertFalse(callbackFinished.get(), "should not have finished");

        future.cancelWithError(true, new IllegalAccessError());
        assertTrue(future.isDone(), "future be done");
        assertTrue(future.isCancelled(), "future should be cancelled");

        assertEventuallyTrue(finished::get, Duration.ofSeconds(1), "should have finished by now");
        assertEventuallyTrue(callbackFinished::get, Duration.ofSeconds(1), "should have finished by now");
    }

    @Test
    @DisplayName("Completion Callback Test")
    void completionCallbackTest() {
        final AtomicBoolean complete = new AtomicBoolean();

        final StandardFuture<Integer> future = new StandardFuture<>(value -> {
            assertFalse(complete.get(), "should only be completed once");
            assertEquals(17, value, "unexpected value");
            complete.set(true);
        });

        future.complete(17);

        assertTrue(complete.get(), "callback not invoked");
    }

    @Test
    @DisplayName("Complete After Complete Test")
    void completeAfterCompleteTest() throws ExecutionException, InterruptedException {
        final StandardFuture<Integer> future = new StandardFuture<>();
        future.complete(0);
        future.complete(1);
        assertEquals(0, future.get(), "unexpected value");
    }

    @Test
    @DisplayName("Complete After Cancellation Test")
    void completeAfterCancellationTest() {
        final StandardFuture<Integer> future = new StandardFuture<>();
        future.cancel();
        future.complete(1);
        assertThrows(CancellationException.class, future::get, "should be cancelled");
    }

    @Test
    @DisplayName("Cancel After Complete Test")
    void cancelAfterCompleteTest() throws ExecutionException, InterruptedException {
        final StandardFuture<Integer> future = new StandardFuture<>();
        future.complete(0);
        future.cancel();
        assertEquals(0, future.get(), "unexpected value");
    }

    @Test
    @DisplayName("Cancel After Cancel Test")
    void cancelAfterCancelTest() {
        final StandardFuture<Integer> future = new StandardFuture<>();
        future.cancel();
        future.cancel();
        assertThrows(CancellationException.class, future::get, "should be cancelled");
    }

    @Test
    @DisplayName("cancelWithError() After Complete Test")
    void cancelWithErrorAfterComplete() throws ExecutionException, InterruptedException {
        final StandardFuture<Integer> future = new StandardFuture<>();
        future.complete(0);
        future.cancelWithError(new RuntimeException("intentional error"));
        assertEquals(0, future.get(), "unexpected value");
    }

    @Test
    @DisplayName("cancelWithError() After Cancellation Test")
    void cancelWithErrorAfterCancel() {
        final StandardFuture<Integer> future = new StandardFuture<>();
        future.cancel();
        future.cancelWithError(new RuntimeException("intentional error"));
        assertThrows(CancellationException.class, future::get, "should be cancelled");
    }
}
