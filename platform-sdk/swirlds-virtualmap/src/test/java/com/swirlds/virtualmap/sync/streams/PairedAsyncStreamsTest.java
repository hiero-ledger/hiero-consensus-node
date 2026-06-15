// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.pool.ParallelExecutionException;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end tests that exercise {@link AsyncInputStream} and {@link AsyncOutputStream} together
 * over a real loopback socket via {@link PairedStreams}. The individual unit tests for each class
 * already cover constructor validation, framing, backpressure, flushing, error propagation, and
 * ordering in isolation, so this suite focuses on scenarios that can only be observed when both
 * background threads are alive on opposite ends of a real socket: a basic round-trip,
 * asymmetric socket teardown from each side, and producer/consumer speed mismatch across the pair.
 */
@Tag(TestComponentTags.RECONNECT)
@DisplayName("Paired AsyncInputStream / AsyncOutputStream Test")
class PairedAsyncStreamsTest {

    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofMillis(50);
    // Generous safety timeout used for the production knobs (readOrWait / sendAsync) that are
    // expected to never fire in a healthy test run. Matches AsyncOutputStreamTest.DEFAULT_TIMEOUT.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("Basic Operation: messages round-trip through the socket and done() terminates cleanly")
    void basicOperation() throws IOException, InterruptedException, ParallelExecutionException {
        try (final PairedStreams streams = new PairedStreams();
                final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "basic")) {

            final AsyncInputStream teacherIn =
                    new AsyncInputStream(streams.getTeacherInput(), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
            final AsyncOutputStream learnerOut = new AsyncOutputStream(
                    streams.getLearnerOutput(), DEFAULT_QUEUE_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);

            teacherIn.start(workGroup);
            learnerOut.start(workGroup);

            final int count = 100;

            OutcomeRunnable learnerRunnable = new OutcomeRunnable(() -> {
                for (int i = 0; i < count; i++) {
                    learnerOut.sendAsync(serializeLong(i));
                }
                learnerOut.done();
            });
            workGroup.fork("learner-sender", learnerRunnable);

            final AtomicInteger messagesRead = new AtomicInteger();
            OutcomeRunnable teacherRunnable = new OutcomeRunnable(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    final byte[] message = teacherIn.readOrWait(YieldStrategy.SPIN);
                    if (message == null) {
                        break;
                    }
                    assertNotNull(message);
                    assertEquals(
                            messagesRead.getAndIncrement(),
                            parseLong(message),
                            "message should match the value that was serialized");
                }
            });
            workGroup.fork("teacher-receiver", teacherRunnable);

            workGroup.join();

            learnerRunnable.verifySuccess("learner task");
            teacherRunnable.verifySuccess("teacher task");
            assertEquals(count, messagesRead.get(), "messages should be read");
        }
    }

    @Test
    @DisplayName("Learner disconnect mid-stream propagates error to work group")
    void learnerDisconnectMidStream() throws IOException, InterruptedException {
        final PairedStreams streams = new PairedStreams();
        try (final StandardWorkGroup workGroup =
                new StandardWorkGroup(getStaticThreadManager(), "teacher-disconnect")) {
            final AsyncInputStream teacherIn =
                    new AsyncInputStream(streams.getTeacherInput(), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
            final AsyncOutputStream learnerOut = new AsyncOutputStream(
                    streams.getLearnerOutput(), DEFAULT_QUEUE_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);

            teacherIn.start(workGroup);
            learnerOut.start(workGroup);

            // Teacher task signals when the first message has been observed so the main thread
            // can disconnect the learner only after the round-trip is complete — avoids the
            // timing-based race of sleeping for "long enough" to assume a flush happened.
            final CountDownLatch firstReceived = new CountDownLatch(1);
            OutcomeRunnable teacherRunnable = new OutcomeRunnable(() -> {
                final byte[] first = teacherIn.readOrWait(YieldStrategy.PARK);
                assertNotNull(first, "first message should arrive before disconnect");
                firstReceived.countDown();
                // second read blocks until the work group interrupts the task after EOF and marks stream as done
                Thread.sleep(DEFAULT_TIMEOUT.toMillis());
            });
            workGroup.fork("teacher-task", teacherRunnable);

            learnerOut.sendAsync(serializeLong(1));
            assertTrue(
                    firstReceived.await(5, TimeUnit.SECONDS), "teacher should observe first message before disconnect");

            streams.disconnectLearner();

            try {
                workGroup.join();
                fail("Should have thrown a ParallelExecutionException");
            } catch (ParallelExecutionException ex) {
                assertInstanceOf(UncheckedIOException.class, ex.getCause(), "UncheckedIOException should be captured");
                assertInstanceOf(EOFException.class, ex.getCause().getCause(), "EOFException should be a cause");
            }

            assertEquals(AsyncInputStream.Status.DONE, teacherIn.getStatus(), "input stream should be closed");
            assertEquals(AsyncOutputStream.Status.DONE, learnerOut.getStatus(), "output stream should be closed");
            teacherRunnable.verifyInterrupted("teacher task");
        } finally {
            closeIgnoringIoException(streams);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Teacher disconnect mid-stream propagates error to work group")
    void teacherDisconnectMidStream() throws IOException, InterruptedException {
        final PairedStreams streams = new PairedStreams();
        try (final StandardWorkGroup workGroup =
                new StandardWorkGroup(getStaticThreadManager(), "learner-disconnect")) {
            final AsyncInputStream teacherIn =
                    new AsyncInputStream(streams.getTeacherInput(), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
            final AsyncOutputStream learnerOut = new AsyncOutputStream(
                    streams.getLearnerOutput(), DEFAULT_QUEUE_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);

            teacherIn.start(workGroup);
            learnerOut.start(workGroup);

            // Close the teacher socket so the learner's write/flush fails with an IOException.
            // Keep pumping messages on a background thread until the work group records the error
            // — the OS may buffer the first few writes before reporting the broken pipe.
            streams.disconnectTeacher();

            OutcomeRunnable learnerRunnable = new OutcomeRunnable(() -> {
                for (int i = 0; i < 10_000 && !Thread.currentThread().isInterrupted(); i++) {
                    learnerOut.sendAsync(serializeLong(i));
                }
            });
            workGroup.fork(learnerRunnable);

            try {
                workGroup.join();
                fail("Should have thrown a ParallelExecutionException");
            } catch (ParallelExecutionException ex) {
                assertInstanceOf(UncheckedIOException.class, ex.getCause(), "UncheckedIOException should be captured");
                assertInstanceOf(SocketException.class, ex.getCause().getCause(), "SocketException should be a cause");
            }

            assertEquals(AsyncInputStream.Status.DONE, teacherIn.getStatus(), "input stream should be closed");
            assertEquals(AsyncOutputStream.Status.DONE, learnerOut.getStatus(), "output stream should be closed");
            learnerRunnable.verifyNotSuccess("learner task");
        } finally {
            closeIgnoringIoException(streams);
        }
    }

    @Test
    @DisplayName("Producer outpaces a slow consumer: all messages arrive in order, done() terminates")
    void slowConsumerBackpressureProducer() throws IOException, InterruptedException, ParallelExecutionException {
        try (final PairedStreams streams = new PairedStreams();
                final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "slow-consumer")) {

            // Small queue so the producer saturates quickly. Generous sendAsync timeout so
            // backpressure blocks the producer instead of throwing while the consumer catches up.
            final int bufferSize = 8;
            final int count = 1000;
            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), bufferSize, DEFAULT_TIMEOUT);
            final AsyncOutputStream out = new AsyncOutputStream(
                    streams.getLearnerOutput(), bufferSize, DEFAULT_FLUSH_INTERVAL, Duration.ofSeconds(30));

            in.start(workGroup);
            out.start(workGroup);

            final AtomicInteger nextExpected = new AtomicInteger(0);
            final Thread consumer = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    final byte[] msg = in.readOrWait(YieldStrategy.SLEEP);
                    if (msg == null) {
                        return;
                    }
                    if (parseLong(msg) != i) {
                        throw new AssertionError("Out of order at index " + i + ", got " + parseLong(msg));
                    }
                    nextExpected.set(i + 1);
                    try {
                        Thread.sleep(1); // slow the consumer to force the producer to wait
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            consumer.setDaemon(true);
            consumer.start();

            // Producer runs on the test thread; sendAsync will block when the queue saturates.
            for (int i = 0; i < count; i++) {
                out.sendAsync(serializeLong(i));
            }

            out.done();
            consumer.join(Duration.ofSeconds(30).toMillis());
            workGroup.join();

            assertEquals(count, nextExpected.get(), "All messages should have been received in order");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Socket SO_TIMEOUT on the input side surfaces as SocketTimeoutException to the work group")
    void socketTimeoutOnBackgroundReadPropagatesToWorkGroup() throws IOException, InterruptedException {
        final PairedStreams streams = new PairedStreams();
        try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "socket-timeout")) {

            // Short SO_TIMEOUT on the teacher socket; the learner never writes anything so the
            // background reader's readInt() will block on the socket until SO_TIMEOUT fires and
            // throws SocketTimeoutException (a subclass of IOException) into AsyncInputStream.run().
            streams.setTeacherTimeout(100);

            final AsyncInputStream teacherIn =
                    new AsyncInputStream(streams.getTeacherInput(), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
            teacherIn.start(workGroup);

            try {
                workGroup.join();
                fail("Should have thrown a ParallelExecutionException");
            } catch (ParallelExecutionException e) {
                assertInstanceOf(
                        UncheckedIOException.class,
                        e.getCause(),
                        "the recorded exception should be UncheckedIOException");
                assertInstanceOf(
                        SocketTimeoutException.class, e.getCause().getCause(), "SocketException should be a cause");
            }

            assertEquals(
                    AsyncInputStream.Status.DONE,
                    teacherIn.getStatus(),
                    "background reader should exit after socket timeout");
        } finally {
            closeIgnoringIoException(streams);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static byte[] serializeLong(final long value) {
        final byte[] bytes = new byte[Long.BYTES];
        BufferedData.wrap(bytes).writeLong(value);
        return bytes;
    }

    private static long parseLong(final byte[] bytes) {
        return BufferedData.wrap(bytes).readLong();
    }

    /**
     * After an intentional asymmetric disconnect, {@link PairedStreams#close()} can throw because
     * flushing a buffered stream over a closed socket fails. Swallow that expected failure during
     * cleanup.
     */
    private static void closeIgnoringIoException(final PairedStreams streams) {
        try {
            streams.close();
        } catch (final IOException ignored) {
            // expected after disconnectTeacher() / disconnectLearner()
        }
    }

    private static class OutcomeRunnable implements Runnable {

        private final ThrowingRunnable delegate;
        private Exception exception;

        OutcomeRunnable(ThrowingRunnable delegate) {
            this.delegate = delegate;
        }

        public void verifySuccess(String context) {
            assertNull(exception, "Runnable should have been successfully finished for " + context);
        }

        public void verifyNotSuccess(String context) {
            assertNotNull(exception, "Runnable should have been interrupted or have error for " + context);
        }

        public void verifyInterrupted(String context) {
            assertInstanceOf(
                    InterruptedException.class, exception, "Runnable should have been interrupted for " + context);
        }

        public void verifyError(Class<? extends Exception> exClass, String context) {
            assertInstanceOf(exClass, exception, "Runnable should have been interrupted for " + context);
        }

        @Override
        public void run() {
            try {
                delegate.run();
                if (Thread.currentThread().isInterrupted()) {
                    exception = new InterruptedException("Thread has been interrupted");
                }
            } catch (Exception ex) {
                exception = ex;
            }
        }
    }
}
