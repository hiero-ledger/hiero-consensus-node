// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyFalse;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.test.fixtures.sync.BlockingInputStream;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag(TestComponentTags.RECONNECT)
class AsyncInputStreamTest {

    /** Default queue size threshold used by tests that don't care about backpressure boundaries. */
    private static final int DEFAULT_QUEUE_SIZE = 100;

    /**
     * Tests to validate constructor, basic properties and methods without starting background thread
     */
    @Nested
    class BasicsWithoutStart {

        @Test
        @DisplayName("Constructor rejects null inputStream")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullInputStream() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncInputStream(null, mock(StandardWorkGroup.class), DEFAULT_QUEUE_SIZE));
        }

        @Test
        @DisplayName("Constructor rejects null workGroup")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullWorkGroup() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncInputStream(mock(DataInputStream.class), null, DEFAULT_QUEUE_SIZE));
        }

        @ParameterizedTest
        @DisplayName("Constructor rejects non-positive queueSizeThreshold")
        @ValueSource(ints = {-1, 0})
        void constructorRejectsBadThreshold(int queueSizeThreshold) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncInputStream(
                            mock(DataInputStream.class), mock(StandardWorkGroup.class), queueSizeThreshold));
        }

        @Test
        @DisplayName("Not alive and queue is empty before start")
        void notAliveAndEmptyBeforeStart() {
            final AsyncInputStream in = new AsyncInputStream(
                    mock(DataInputStream.class), mock(StandardWorkGroup.class), DEFAULT_QUEUE_SIZE);

            assertEquals(AsyncInputStream.Status.NOT_STARTED, in.getStatus(), "status should be NOT_STARTED");
            assertFalse(in.isAlive(), "isAlive must be false before start()");
            assertEquals(0, in.getInputQueueSize(), "queue size should be empty");
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("readOrWait returns null on a stream that was never started")
        void readOrWaitReturnNullAndOnNotStartedStream(final YieldStrategy yieldStrategy) {
            final AsyncInputStream in = new AsyncInputStream(
                    mock(DataInputStream.class), mock(StandardWorkGroup.class), DEFAULT_QUEUE_SIZE);
            assertNull(in.readOrWait(yieldStrategy), "Queue is empty and stream is not alive to return any message");
        }
    }

    /**
     * Tests to validate normal work with single thread and no exceptions
     */
    @Nested
    class SingleThreadBasics {

        /**
         * Create and start input stream reading data from predefined bytes array.
         *
         * @param data predefined data
         * @param consumerTest test consuming async input stream - must read all messages
         */
        private void createStreamAndTest(final byte[] data, final Consumer<AsyncInputStream> consumerTest) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);
            final AsyncInputStream in = new AsyncInputStream(
                    new DataInputStream(new ByteArrayInputStream(data)), workGroup, DEFAULT_QUEUE_SIZE);
            in.start();

            testAndAwaitTermination(workGroup, () -> {
                // wait for background thread should read all the data
                assertEventuallyFalse(
                        in::isAlive,
                        Duration.ofSeconds(3),
                        "Stream should not be alive after termination marker is read");
                assertEquals(AsyncInputStream.Status.DONE, in.getStatus(), "status should be DONE after termination");

                consumerTest.accept(in);

                assertNull(in.readOrWait(YieldStrategy.SPIN), "Stream should be drained after all messages");
            });

            // verify not able to start after termination
            assertThrows(IllegalStateException.class, in::start, "Should have thrown an exception");
        }

        @Test
        @DisplayName("No messages correct behavior")
        void emptyData() throws IOException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            dataOut.writeInt(-1); // termination
            dataOut.flush();

            createStreamAndTest(
                    byteOut.toByteArray(),
                    in -> assertEquals(0, in.getInputQueueSize(), "Input queue size should be empty"));
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("Less than queue size messages decoded in order under each YieldStrategy")
        void lessMessagesThanQueueSize(final YieldStrategy yield) throws IOException {
            final int count = DEFAULT_QUEUE_SIZE - 1;

            createStreamAndTest(encodeLongs(count), in -> {
                assertEquals(count, in.getInputQueueSize(), "Input queue size should match number of messages");

                for (int i = 0; i < count; i++) {
                    final byte[] msg = in.readOrWait(yield);
                    assertNotNull(msg, "Message " + i + " should be available");
                    assertEquals(i, parseLong(msg), "Message " + i + " value should match");
                }
            });
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("More than queue size messages decoded in order under each YieldStrategy")
        void moreMessagesThanQueueSize(final YieldStrategy yield) throws IOException {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);
            final AsyncInputStream in = new AsyncInputStream(
                    new DataInputStream(new ByteArrayInputStream(encodeLongs(DEFAULT_QUEUE_SIZE))),
                    workGroup,
                    DEFAULT_QUEUE_SIZE);
            in.start();

            testAndAwaitTermination(workGroup, () -> {
                assertEventuallyEquals(
                        DEFAULT_QUEUE_SIZE,
                        in::getInputQueueSize,
                        Duration.ofSeconds(3),
                        "Background message queue should fill up");
                assertTrue(
                        in.isAlive(),
                        "Stream should be alive since termination marker is not yet read due to full queue");

                // reading only one message should unblock background thread to read termination marker
                int i = 0;
                byte[] msg = in.readOrWait(yield);
                assertNotNull(msg, "Message " + i + " should be available");
                assertEquals(i, parseLong(msg), "Message " + i + " value should match");

                assertEventuallyFalse(
                        in::isAlive,
                        Duration.ofSeconds(3),
                        "Stream should not be alive after termination marker is read");

                // drain all other messages and verify
                i++;
                for (; i < DEFAULT_QUEUE_SIZE; i++) {
                    msg = in.readOrWait(yield);
                    assertNotNull(msg, "Message " + i + " should be available");
                    assertEquals(i, parseLong(msg), "Message " + i + " value should match");
                }

                assertNull(in.readOrWait(YieldStrategy.SPIN), "Stream should be drained after all messages");
            });
        }

        @Test
        @DisplayName("Zero-length payload round-trips as an empty byte array")
        void zeroLengthPayloadRoundTrips() throws IOException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            dataOut.writeInt(0); // empty payload
            dataOut.writeInt(-1); // termination
            dataOut.flush();

            createStreamAndTest(byteOut.toByteArray(), in -> {
                final byte[] msg = in.readOrWait(YieldStrategy.SPIN);
                assertNotNull(msg, "Zero-length payload should still produce a (non-null) byte array");
                assertEquals(0, msg.length, "Payload should be empty");
            });
        }

        @Test
        @DisplayName("Large message (1 MiB) round-trips byte-exact")
        void largeMessageRoundTrip() throws IOException {
            final byte[] payload = new byte[1 << 20]; // 1 MiB
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            dataOut.writeInt(payload.length);
            dataOut.write(payload);
            dataOut.writeInt(-1);
            dataOut.flush();

            createStreamAndTest(byteOut.toByteArray(), in -> {
                final byte[] received = in.readOrWait(YieldStrategy.PARK);
                assertNotNull(received, "Large message should arrive");
                assertEquals(payload.length, received.length, "Large message length must match");
                assertArrayEquals(payload, received, "Large message bytes must round-trip exactly");
            });
        }
    }

    /**
     * Tests to validate exception handling and propagation.
     */
    @Nested
    class Exceptions {

        @Test
        @DisplayName("Second start throws an exception")
        void secondStartThrowsAnException() {
            final StandardWorkGroup workGroup = mock(StandardWorkGroup.class);
            final DataInputStream inputStream = mock(DataInputStream.class);
            final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, DEFAULT_QUEUE_SIZE);

            in.start();

            assertTrue(in.isAlive(), "Stream should be alive");
            assertEquals(AsyncInputStream.Status.RUNNING, in.getStatus(), "status should be RUNNING after start");
            assertThrows(IllegalStateException.class, in::start, "Second start should throw an exception");

            verify(workGroup, times(1)).execute(eq("async-input-stream"), any(Runnable.class));
            verifyNoMoreInteractions(workGroup);
            verifyNoInteractions(inputStream);
        }

        @Test
        @DisplayName("Background thread failed to submit")
        void backgroundReadThreadFailsToBeSubmitted() {
            final RuntimeException cause = new RuntimeException();
            final StandardWorkGroup workGroup = mock(StandardWorkGroup.class);
            final DataInputStream inputStream = mock(DataInputStream.class);

            doThrow(cause).when(workGroup).execute(eq("async-input-stream"), any(Runnable.class));

            final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, DEFAULT_QUEUE_SIZE);
            assertThrows(MerkleSynchronizationException.class, in::start, "Should have thrown an exception");
            assertFalse(in.isAlive(), "Stream should be alive");
            assertEquals(AsyncInputStream.Status.DONE, in.getStatus(), "status should be DONE after failed start");

            verify(workGroup, times(1)).execute(eq("async-input-stream"), any(Runnable.class));
            verify(workGroup, times(1)).handleError(cause);
            verifyNoMoreInteractions(workGroup);
            verifyNoInteractions(inputStream);
        }

        @Test
        @DisplayName("Too big message read causes exception")
        void tooBigMessageReadThrowsException() throws InterruptedException, IOException {
            final byte[] payload = new byte[AsyncInputStream.MAX_MESSAGE_SIZE + 1];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            dataOut.writeInt(payload.length);
            dataOut.write(payload);
            dataOut.writeInt(-1);
            dataOut.flush();

            final ExceptionCapture exceptionCapture = new ExceptionCapture();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "test", null, exceptionCapture);
            final DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
            final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, DEFAULT_QUEUE_SIZE);

            in.start();
            workGroup.waitForTermination();

            assertFalse(in.isAlive(), "Stream should not be alive");
            assertTrue(workGroup.hasExceptions(), "Work group should have an exception");
            assertEquals(1, exceptionCapture.getExceptions().size());
            assertInstanceOf(
                    MerkleSynchronizationException.class,
                    exceptionCapture.getExceptions().peek());
            assertTrue(
                    exceptionCapture.getExceptions().peek().getMessage().contains("Message size exceeds maximum size"));

            // verify not able to start after error
            assertThrows(IllegalStateException.class, in::start, "Should have thrown an exception");
        }

        @Test
        @DisplayName("EOFexception is propagated to work group")
        void eofExceptionIsPropagatedToWorkGroup() throws InterruptedException {
            final ExceptionCapture exceptionCapture = new ExceptionCapture();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "test", null, exceptionCapture);
            final DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(new byte[0]));
            final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, DEFAULT_QUEUE_SIZE);

            in.start();
            workGroup.waitForTermination();

            assertFalse(in.isAlive(), "Stream should not be alive");
            assertTrue(workGroup.hasExceptions(), "Work group should have an exception");
            assertEquals(1, exceptionCapture.getExceptions().size());
            assertInstanceOf(
                    EOFException.class, exceptionCapture.getExceptions().peek());

            // verify not able to start after error
            assertThrows(IllegalStateException.class, in::start, "Should have thrown an exception");
        }

        @Test
        @DisplayName("IOException is propagated to work group")
        void ioExceptionIsPropagatedToWorkGroup() throws InterruptedException, IOException {
            final String error = "broken stream";
            final ExceptionCapture exceptionCapture = new ExceptionCapture();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "test", null, exceptionCapture);

            final DataInputStream inputStream = mock(DataInputStream.class);
            when(inputStream.readInt()).thenThrow(new IOException(error));

            final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, DEFAULT_QUEUE_SIZE);

            in.start();
            workGroup.waitForTermination();

            assertFalse(in.isAlive(), "Stream should not be alive");
            assertTrue(workGroup.hasExceptions(), "Work group should have an exception");
            assertEquals(1, exceptionCapture.getExceptions().size());
            assertInstanceOf(IOException.class, exceptionCapture.getExceptions().peek());
            assertEquals(error, exceptionCapture.getExceptions().peek().getMessage());
        }

        @Test
        @DisplayName("Socket SO_TIMEOUT propagates SocketTimeoutException to the work group")
        void socketTimeoutPropagatesError() throws IOException {
            final ExceptionCapture exceptionCapture = new ExceptionCapture();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "socket-timeout", null, exceptionCapture);

            try (final PairedStreams streams = new PairedStreams()) {
                streams.setLearnerTimeout(100); // 100 ms read timeout

                final AsyncInputStream in =
                        new AsyncInputStream(streams.getLearnerInput(), workGroup, DEFAULT_QUEUE_SIZE);
                in.start();
                // Teacher never writes anything - learner's readInt() will throw SocketTimeoutException.

                testAndAwaitTermination(
                        workGroup,
                        () -> assertEventuallyFalse(in::isAlive, Duration.ofSeconds(5), "Stream should not be alive"));

                assertTrue(workGroup.hasExceptions(), "Work group should have an exception");
                assertEquals(1, exceptionCapture.getExceptions().size());
                assertInstanceOf(
                        SocketTimeoutException.class,
                        exceptionCapture.getExceptions().peek());
            }
        }

        @Test
        @DisplayName("Truncated wire frame propagates an IOException to the work group")
        void truncatedFramePropagatesError() throws IOException {
            final ExceptionCapture exceptionCapture = new ExceptionCapture();

            final PairedStreams streams = new PairedStreams();
            try {
                final StandardWorkGroup workGroup =
                        new StandardWorkGroup(getStaticThreadManager(), "truncated-frame", null, exceptionCapture);
                final AsyncInputStream in =
                        new AsyncInputStream(streams.getLearnerInput(), workGroup, DEFAULT_QUEUE_SIZE);
                in.start();

                // Write length=10 but only 3 bytes of payload, then drop the connection.
                final DataOutputStream teacherOut = streams.getTeacherOutput();
                teacherOut.writeInt(10);
                teacherOut.write(new byte[] {1, 2, 3});
                teacherOut.flush();
                streams.disconnectTeacher();

                testAndAwaitTermination(
                        workGroup,
                        () -> assertEventuallyFalse(in::isAlive, Duration.ofSeconds(5), "Stream should not be alive"));

                assertTrue(workGroup.hasExceptions(), "Work group should have an exception");
                assertEquals(1, exceptionCapture.getExceptions().size());
                assertInstanceOf(
                        IOException.class, exceptionCapture.getExceptions().peek());
            } finally {
                closeIgnoringIoException(streams);
            }
        }
    }

    /**
     * Tests for concurrent consumption, blocking, interruption.
     */
    @Nested
    class Concurrent {

        @Test
        @DisplayName("Concurrent starts throws an exception")
        void concurrentStartsThrowsAnException() throws InterruptedException {
            final StandardWorkGroup workGroup = mock(StandardWorkGroup.class);
            final DataInputStream inputStream = mock(DataInputStream.class);
            final AsyncInputStream in = new AsyncInputStream(inputStream, workGroup, DEFAULT_QUEUE_SIZE);

            final int threadsCount = 10;
            final AtomicInteger exceptionsCount = new AtomicInteger();
            final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
            final CountDownLatch doneLatch = new CountDownLatch(threadsCount);

            for (int i = 0; i < threadsCount; i++) {
                new Thread(() -> {
                            try {
                                barrier.await();
                                in.start();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Interrupted", e);
                            } catch (BrokenBarrierException e) {
                                throw new RuntimeException(e);
                            } catch (IllegalStateException e) {
                                exceptionsCount.incrementAndGet();
                            } finally {
                                doneLatch.countDown();
                            }
                        })
                        .start();
            }

            if (!doneLatch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("No all threads starting stream finished");
            }
            assertEquals(
                    threadsCount - 1,
                    exceptionsCount.get(),
                    "Only one thread can start, others should throw an exception");
            verify(workGroup, times(1)).execute(eq("async-input-stream"), any(Runnable.class));
            verifyNoMoreInteractions(workGroup);
            verifyNoInteractions(inputStream);
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("readOrWait returns null when the calling thread is interrupted")
        void readOrWaitReturnsNullOnInterrupt(final YieldStrategy yieldStrategy) throws IOException {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "interrupted", null);
            // Lock the underlying stream so the reader thread blocks inside readInt() and alive
            // stays true - this forces readOrWait to exit via the interrupt branch, not !alive.
            final BlockingInputStream blockingIn = new BlockingInputStream(new ByteArrayInputStream(encodeLongs(1)));
            blockingIn.lock();
            final AsyncInputStream in =
                    new AsyncInputStream(new DataInputStream(blockingIn), workGroup, DEFAULT_QUEUE_SIZE);
            in.start();

            testAndAwaitTermination(workGroup, () -> {
                assertTrue(in.isAlive(), "Reader thread should be alive");

                final CountDownLatch startLatch = new CountDownLatch(1);
                final byte[][] result = new byte[1][];
                final Thread caller = new Thread(() -> {
                    startLatch.countDown();
                    result[0] = in.readOrWait(yieldStrategy);
                });
                caller.setDaemon(true);
                caller.start();

                assertTrue(startLatch.await(3, TimeUnit.SECONDS), "Consumer thread should be started");
                // Give the caller a moment to enter the spin loop, then interrupt it from outside.
                MILLISECONDS.sleep(50);
                caller.interrupt(); // interrupt waiting for a message
                caller.join(2_000);

                assertFalse(caller.isAlive(), "Caller thread should exit after interrupt");
                assertNull(result[0], "readOrWait should return null when the caller is interrupted");

                // Unblock so the reader thread can finish and the work group can terminate.
                blockingIn.unlock();
            });
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("Concurrent read messages")
        void concurrentReadMessages(final YieldStrategy yieldStrategy) throws IOException {
            final int threadsCount = 10;
            final int messageCount = DEFAULT_QUEUE_SIZE * threadsCount;

            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);
            final AsyncInputStream in = new AsyncInputStream(
                    new DataInputStream(new ByteArrayInputStream(encodeLongs(messageCount))),
                    workGroup,
                    DEFAULT_QUEUE_SIZE);
            in.start();

            final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
            final CountDownLatch doneLatch = new CountDownLatch(threadsCount);
            final Thread[] threads = new Thread[threadsCount];
            final ConcurrentLinkedQueue<Long> received = new ConcurrentLinkedQueue<>();

            testAndAwaitTermination(workGroup, () -> {
                for (int i = 0; i < threadsCount; i++) {
                    Thread thread = new Thread(() -> {
                        try {
                            barrier.await();
                            while (true) {
                                final byte[] msg = in.readOrWait(yieldStrategy);
                                if (msg == null) {
                                    return;
                                }
                                received.add(parseLong(msg));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted", e);
                        } catch (BrokenBarrierException e) {
                            throw new RuntimeException(e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                    thread.setDaemon(true);
                    thread.start();
                    threads[i] = thread;
                }

                if (!doneLatch.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Not all consumer threads finished");
                }
            });

            assertEquals(messageCount, received.size(), "Every message should have been delivered exactly once");
            final Set<Long> distinct = new HashSet<>(received);
            assertEquals(messageCount, distinct.size(), "No duplicate deliveries across consumers");
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static long parseLong(final byte[] bytes) {
        return BufferedData.wrap(bytes).readLong();
    }

    /** Writes {@code count} long-valued messages followed by the termination marker. */
    private static byte[] encodeLongs(final int count) throws IOException {
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final DataOutputStream dataOut = new DataOutputStream(byteOut);
        for (int i = 0; i < count; i++) {
            dataOut.writeInt(Long.BYTES);
            dataOut.writeLong(i);
        }
        dataOut.writeInt(-1); // termination marker
        dataOut.flush();
        return byteOut.toByteArray();
    }

    /**
     * After an intentional disconnect, {@link PairedStreams#close()} can throw because flushing a
     * buffered stream over a closed socket fails. Swallow that expected failure during cleanup.
     */
    private static void closeIgnoringIoException(final PairedStreams streams) {
        try {
            streams.close();
        } catch (final IOException ignored) {
            // expected after disconnectTeacher() / disconnectLearner()
        }
    }

    private static void testAndAwaitTermination(StandardWorkGroup workGroup, ThrowingRunnable test) {
        try {
            test.run();
            workGroup.waitForTermination();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", ex);
        } catch (Exception ex) {
            workGroup.handleError(ex); // cancel all submitted tasks in case of any error inside test (like assertion)
            throw new RuntimeException(ex);
        } catch (Throwable ex) {
            workGroup.handleError(ex); // cancel all submitted tasks in case of any error inside test (like assertion)
            throw ex;
        }
    }
}
