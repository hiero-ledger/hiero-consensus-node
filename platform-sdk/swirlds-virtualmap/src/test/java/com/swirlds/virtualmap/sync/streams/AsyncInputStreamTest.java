// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.pool.ParallelExecutionException;
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

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

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
                    NullPointerException.class, () -> new AsyncInputStream(null, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT));
        }

        @ParameterizedTest
        @DisplayName("Constructor rejects non-positive queueSizeThreshold")
        @ValueSource(ints = {-1, 0})
        void constructorRejectsBadThreshold(int queueSizeThreshold) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncInputStream(mock(DataInputStream.class), queueSizeThreshold, DEFAULT_TIMEOUT));
        }

        @Test
        @DisplayName("Constructor rejects null timeout")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullTimeout() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncInputStream(mock(DataInputStream.class), DEFAULT_QUEUE_SIZE, null));
        }

        @ParameterizedTest
        @ValueSource(longs = {-1L, 0L})
        @DisplayName("Constructor rejects non-positive timeout")
        void constructorRejectsBadTimeout(long timeoutMs) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncInputStream(
                            mock(DataInputStream.class), DEFAULT_QUEUE_SIZE, Duration.ofMillis(timeoutMs)));
        }

        @Test
        @DisplayName("Status is NOT_STARTED and queue is empty before start")
        void notStartedAndEmptyBeforeStart() {
            final AsyncInputStream in =
                    new AsyncInputStream(mock(DataInputStream.class), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);

            assertEquals(AsyncInputStream.Status.NOT_STARTED, in.getStatus(), "status should be NOT_STARTED");
            assertEquals(0, in.getQueueSize(), "queue size should be empty");
        }

        @Test
        @DisplayName("Start fails with null workGroup")
        @SuppressWarnings("DataFlowIssue")
        void startRejectsNullWorkGroup() {
            AsyncInputStream stream =
                    new AsyncInputStream(mock(DataInputStream.class), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
            assertThrows(NullPointerException.class, () -> stream.start(null));
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("read returns null on a stream that was never started")
        void readReturnsNullOnNotStartedStream(final YieldStrategy yieldStrategy) {
            final AsyncInputStream in =
                    new AsyncInputStream(mock(DataInputStream.class), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
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
         * @param beforeDone test consuming async input stream to run before waiting for background thread to be done
         * @param afterDone test consuming async input stream to run after background stream is done - must read all messages
         */
        private void createStreamAndTest(
                final byte[] data,
                final Consumer<AsyncInputStream> beforeDone,
                final Consumer<AsyncInputStream> afterDone)
                throws ParallelExecutionException, InterruptedException {
            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test")) {
                final AsyncInputStream in = new AsyncInputStream(
                        new DataInputStream(new ByteArrayInputStream(data)), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
                in.start(workGroup);

                beforeDone.accept(in);

                // wait for background thread read all the data
                assertEventuallyEquals(
                        AsyncInputStream.Status.DONE,
                        in::getStatus,
                        Duration.ofSeconds(3),
                        "Stream should not be alive after termination marker is read");

                afterDone.accept(in);

                assertNull(in.readOrWait(YieldStrategy.SPIN), "Stream should be drained after all messages");
                assertEquals(0, in.getQueueSize(), "buffer size should be empty");

                workGroup.join();

                // verify not able to start after termination
                assertThrows(IllegalStateException.class, () -> in.start(workGroup), "Should have thrown an exception");
            }
        }

        @Test
        @DisplayName("No messages correct behavior")
        void emptyData() throws IOException, ParallelExecutionException, InterruptedException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            dataOut.writeInt(-1); // termination
            dataOut.flush();

            createStreamAndTest(byteOut.toByteArray(), _ -> {}, _ -> {});
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("Less than queue size messages decoded in order under each YieldStrategy")
        void lessMessagesThanQueueSize(final YieldStrategy yield)
                throws IOException, ParallelExecutionException, InterruptedException {
            final int count = DEFAULT_QUEUE_SIZE - 1;

            createStreamAndTest(encodeLongs(count), _ -> {}, in -> {
                assertEquals(count, in.getQueueSize(), "Input queue size should match number of messages");

                for (int i = 0; i < count; i++) {
                    final byte[] msg = in.readOrWait(yield);
                    assertNotNull(msg, "Message " + i + " should be available");
                    assertEquals(i, parseLong(msg), "Message " + i + " value should match");
                }
            });
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("Full queue keep background stream running")
        void fullQueueKeepsStreamRunning(final YieldStrategy yield)
                throws IOException, ParallelExecutionException, InterruptedException {
            createStreamAndTest(
                    encodeLongs(DEFAULT_QUEUE_SIZE),
                    in -> {
                        assertEventuallyEquals(
                                DEFAULT_QUEUE_SIZE,
                                in::getQueueSize,
                                Duration.ofSeconds(3),
                                "Background message queue should fill up");
                        assertEquals(
                                AsyncInputStream.Status.RUNNING,
                                in.getStatus(),
                                "Stream should be alive since termination marker is not yet read due to full queue");

                        // reading only one message should unblock background thread to read termination marker
                        int i = 0;
                        byte[] msg = in.readOrWait(yield);
                        assertNotNull(msg, "Message " + i + " should be available");
                        assertEquals(i, parseLong(msg), "Message " + i + " value should match");
                    },
                    in -> {
                        // drain all other messages and verify
                        for (int i = 1; i < DEFAULT_QUEUE_SIZE; i++) {
                            byte[] msg = in.readOrWait(yield);
                            assertNotNull(msg, "Message " + i + " should be available");
                            assertEquals(i, parseLong(msg), "Message " + i + " value should match");
                        }
                    });
        }

        @Test
        @DisplayName("Zero-length payload round-trips as an empty byte array")
        void zeroLengthPayloadRoundTrips() throws IOException, ParallelExecutionException, InterruptedException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final DataOutputStream dataOut = new DataOutputStream(byteOut);
            dataOut.writeInt(0); // empty payload
            dataOut.writeInt(-1); // termination
            dataOut.flush();

            createStreamAndTest(byteOut.toByteArray(), _ -> {}, in -> {
                final byte[] msg = in.readOrWait(YieldStrategy.SPIN);
                assertNotNull(msg, "Zero-length payload should still produce a (non-null) byte array");
                assertEquals(0, msg.length, "Payload should be empty");
            });
        }

        @Test
        @DisplayName("Large message (1 MiB) round-trips byte-exact")
        void largeMessageRoundTrip() throws IOException, ParallelExecutionException, InterruptedException {
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

            createStreamAndTest(byteOut.toByteArray(), _ -> {}, in -> {
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
            final AsyncInputStream in = new AsyncInputStream(inputStream, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);

            in.start(workGroup);

            assertEquals(AsyncInputStream.Status.RUNNING, in.getStatus(), "status should be RUNNING after start");
            assertThrows(
                    IllegalStateException.class, () -> in.start(workGroup), "Second start should throw an exception");

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

            final AsyncInputStream in = new AsyncInputStream(inputStream, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
            try {
                in.start(workGroup);
                fail("Exception should have been thrown");
            } catch (RuntimeException e) {
                assertSame(cause, e, "Exception thrown by work group should be propagated");
            }

            assertEquals(AsyncInputStream.Status.DONE, in.getStatus(), "status should be DONE after failed start");

            verify(workGroup, times(1)).execute(eq("async-input-stream"), any(Runnable.class));
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

            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test")) {
                final DataInputStream inputStream =
                        new DataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
                final AsyncInputStream in = new AsyncInputStream(inputStream, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);

                in.start(workGroup);

                try {
                    workGroup.join();
                    fail("Should have thrown a ParallelExecutionException");
                } catch (ParallelExecutionException ex) {
                    assertInstanceOf(
                            MerkleSynchronizationException.class,
                            ex.getCause(),
                            "MerkleSynchronizationException should be captured");
                    assertTrue(ex.getCause().getMessage().contains("Message size exceeds maximum size"));
                }

                assertEquals(AsyncInputStream.Status.DONE, in.getStatus(), "Reader thread should be done");

                // verify not able to start after error
                assertThrows(IllegalStateException.class, () -> in.start(workGroup), "Should have thrown an exception");
            }
        }

        @Test
        @DisplayName("EOFexception is propagated from work group")
        void eofExceptionIsPropagated() throws InterruptedException {
            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test")) {
                final DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(new byte[0]));
                final AsyncInputStream in = new AsyncInputStream(inputStream, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);

                in.start(workGroup);

                try {
                    workGroup.join();
                    fail("Should have thrown a ParallelExecutionException");
                } catch (ParallelExecutionException ex) {
                    assertInstanceOf(
                            UncheckedIOException.class, ex.getCause(), "UncheckedIOException should be captured");
                    assertInstanceOf(EOFException.class, ex.getCause().getCause(), "EOFException should be a cause");
                }

                assertEquals(AsyncInputStream.Status.DONE, in.getStatus(), "Reader thread should be done");

                // verify not able to start after error
                assertThrows(IllegalStateException.class, () -> in.start(workGroup), "Should have thrown an exception");
            }
        }

        @Test
        @DisplayName("IOException is propagated from work group")
        void ioExceptionIsPropagated() throws InterruptedException, IOException {
            final String error = "broken stream";
            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test")) {
                final DataInputStream inputStream = mock(DataInputStream.class);
                when(inputStream.readInt()).thenThrow(new IOException(error));

                final AsyncInputStream in = new AsyncInputStream(inputStream, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);

                in.start(workGroup);

                try {
                    workGroup.join();
                    fail("Should have thrown a ParallelExecutionException");
                } catch (ParallelExecutionException ex) {
                    assertInstanceOf(UncheckedIOException.class, ex.getCause(), "EOFException should be captured");
                    assertEquals(error, ex.getCause().getCause().getMessage());
                }

                assertEquals(AsyncInputStream.Status.DONE, in.getStatus(), "Reader thread should be done");
            }
        }

        @Test
        @DisplayName("Consumer timeout if no messages are available in stream")
        void consumerTimeoutOnBlockingStream() throws IOException, InterruptedException, ParallelExecutionException {
            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "timeout")) {
                final BlockingInputStream blockingIn =
                        new BlockingInputStream(new ByteArrayInputStream(encodeLongs(1)));
                blockingIn.lock();
                final AsyncInputStream in = new AsyncInputStream(
                        new DataInputStream(blockingIn), DEFAULT_QUEUE_SIZE, Duration.ofMillis(100));
                in.start(workGroup);

                assertThrows(
                        MerkleSynchronizationException.class,
                        () -> in.readOrWait(YieldStrategy.PARK),
                        "Should time out");

                blockingIn.unlock();
                workGroup.join();
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
            final AsyncInputStream in = new AsyncInputStream(inputStream, DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);

            final int threadsCount = 10;
            final AtomicInteger exceptionsCount = new AtomicInteger();
            final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
            final CountDownLatch doneLatch = new CountDownLatch(threadsCount);

            for (int i = 0; i < threadsCount; i++) {
                new Thread(() -> {
                            try {
                                barrier.await();
                                in.start(workGroup);
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
        @DisplayName("read returns null when the calling thread is interrupted")
        void readReturnsNullOnInterrupt(final YieldStrategy yieldStrategy)
                throws IOException, InterruptedException, ParallelExecutionException {
            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "interrupted")) {
                // Lock the underlying stream so the reader thread blocks inside readInt() and alive
                // stays true - this forces read to exit via the interrupt branch, not !alive.
                final BlockingInputStream blockingIn =
                        new BlockingInputStream(new ByteArrayInputStream(encodeLongs(1)));
                blockingIn.lock();
                final AsyncInputStream in =
                        new AsyncInputStream(new DataInputStream(blockingIn), DEFAULT_QUEUE_SIZE, DEFAULT_TIMEOUT);
                in.start(workGroup);

                assertEquals(AsyncInputStream.Status.RUNNING, in.getStatus(), "Reader thread should be running");

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
                assertNull(result[0], "read should return null when the caller is interrupted");

                // Unblock so the reader thread can finish and the work group can terminate.
                blockingIn.unlock();
                workGroup.join();
            }
        }

        @ParameterizedTest
        @EnumSource(YieldStrategy.class)
        @DisplayName("Concurrent read messages")
        void concurrentReadMessages(final YieldStrategy yieldStrategy)
                throws IOException, ParallelExecutionException, InterruptedException {
            final int threadsCount = 10;
            final int messageCount = DEFAULT_QUEUE_SIZE * threadsCount;

            try (final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test")) {
                final AsyncInputStream in = new AsyncInputStream(
                        new DataInputStream(new ByteArrayInputStream(encodeLongs(messageCount))),
                        DEFAULT_QUEUE_SIZE,
                        DEFAULT_TIMEOUT);
                in.start(workGroup);

                final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
                final CountDownLatch doneLatch = new CountDownLatch(threadsCount);
                final ConcurrentLinkedQueue<Long> received = new ConcurrentLinkedQueue<>();

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
                }

                if (!doneLatch.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Not all consumer threads finished");
                }

                workGroup.join();

                assertEquals(messageCount, received.size(), "Every message should have been delivered exactly once");
                final Set<Long> distinct = new HashSet<>(received);
                assertEquals(messageCount, distinct.size(), "No duplicate deliveries across consumers");
            }
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
}
