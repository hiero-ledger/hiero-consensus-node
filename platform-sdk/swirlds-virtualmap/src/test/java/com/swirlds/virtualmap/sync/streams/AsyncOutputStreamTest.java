// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.test.fixtures.sync.BlockingOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag(TestComponentTags.RECONNECT)
class AsyncOutputStreamTest {

    private static final int DEFAULT_BUFFER_SIZE = 100;
    private static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofMillis(50);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Tests to validate constructor, basic properties and methods without starting background thread.
     */
    @Nested
    class BasicsWithoutStart {

        @Test
        @DisplayName("Constructor rejects null outputStream")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullOutputStream() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncOutputStream(
                            null,
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            DEFAULT_FLUSH_INTERVAL,
                            DEFAULT_TIMEOUT));
        }

        @Test
        @DisplayName("Constructor rejects null workGroup")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullWorkGroup() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            null,
                            DEFAULT_BUFFER_SIZE,
                            DEFAULT_FLUSH_INTERVAL,
                            DEFAULT_TIMEOUT));
        }

        @Test
        @DisplayName("Constructor rejects null flushInterval")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullFlushInterval() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            null,
                            DEFAULT_TIMEOUT));
        }

        @Test
        @DisplayName("Constructor rejects null timeout")
        @SuppressWarnings("DataFlowIssue")
        void constructorRejectsNullTimeout() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            DEFAULT_FLUSH_INTERVAL,
                            null));
        }

        @ParameterizedTest
        @DisplayName("Constructor rejects non-positive bufferSize")
        @ValueSource(ints = {-1, 0})
        void constructorRejectsBadBufferSize(int bufferSize) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            bufferSize,
                            DEFAULT_FLUSH_INTERVAL,
                            DEFAULT_TIMEOUT));
        }

        @Test
        @DisplayName("Constructor rejects non-positive flushInterval")
        void constructorRejectsBadFlushInterval() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            Duration.ZERO,
                            DEFAULT_TIMEOUT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            Duration.ofMillis(-1),
                            DEFAULT_TIMEOUT));
        }

        @Test
        @DisplayName("Constructor rejects non-positive timeout")
        void constructorRejectsBadTimeout() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            DEFAULT_FLUSH_INTERVAL,
                            Duration.ZERO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AsyncOutputStream(
                            mock(DataOutputStream.class),
                            mock(StandardWorkGroup.class),
                            DEFAULT_BUFFER_SIZE,
                            DEFAULT_FLUSH_INTERVAL,
                            Duration.ofMillis(-1)));
        }

        @Test
        @DisplayName("Status is NOT_STARTED and isAlive is false before start")
        void notAliveBeforeStart() {
            final AsyncOutputStream out = newOut(mock(DataOutputStream.class), mock(StandardWorkGroup.class));
            assertEquals(AsyncOutputStream.Status.NOT_STARTED, out.getStatus());
            assertFalse(out.isAlive(), "isAlive must be false before start()");
        }

        @Test
        @DisplayName("sendAsync before start throws IllegalStateException")
        void sendAsyncBeforeStartThrows() {
            final AsyncOutputStream out = newOut(mock(DataOutputStream.class), mock(StandardWorkGroup.class));
            assertThrows(IllegalStateException.class, () -> out.sendAsync(serializeLong(1)));
        }

        @Test
        @DisplayName("done() before start is a no-op")
        void doneBeforeStartIsNoOp() {
            final AsyncOutputStream out = newOut(mock(DataOutputStream.class), mock(StandardWorkGroup.class));
            out.done(); // should not throw
            assertEquals(AsyncOutputStream.Status.NOT_STARTED, out.getStatus());
        }
    }

    /**
     * Tests to validate normal work with single thread and no exceptions.
     */
    @Nested
    class SingleThreadBasics {

        @Test
        @DisplayName("done() with no messages writes only the termination marker")
        void doneWithNoMessagesWritesOnlyMarker() throws IOException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "no-msgs", null);
            final AsyncOutputStream out = newOut(new DataOutputStream(byteOut), workGroup);
            out.start();
            assertEquals(AsyncOutputStream.Status.RUNNING, out.getStatus());

            testAndAwaitTermination(workGroup, out::done);

            assertEquals(AsyncOutputStream.Status.DONE, out.getStatus());
            assertFalse(out.isAlive(), "Stream should not be alive after termination");

            verifyOrderedMessages(0, byteOut.toByteArray());
        }

        @Test
        @DisplayName("Less than buffer size messages drain in order followed by -1")
        void lessMessagesThanBuffer() throws IOException {
            final int count = DEFAULT_BUFFER_SIZE - 1;
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "drain-less", null);
            final AsyncOutputStream out = newOut(new DataOutputStream(byteOut), workGroup);
            out.start();

            testAndAwaitTermination(workGroup, () -> {
                for (int i = 0; i < count; i++) {
                    out.sendAsync(serializeLong(i));
                }
                out.done();
            });

            assertEquals(AsyncOutputStream.Status.DONE, out.getStatus());
            verifyOrderedMessages(count, byteOut.toByteArray());
        }

        @Test
        @DisplayName("More than buffer size messages — backpressure works, all messages delivered")
        void moreMessagesThanBuffer() throws IOException, InterruptedException {
            final int bufferSize = 16;
            final int count = bufferSize * 100;
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final BlockingOutputStream blockingOut = new BlockingOutputStream(byteOut);
            blockingOut.lock();

            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "drain-more", null);
            final AsyncOutputStream out = new AsyncOutputStream(
                    new DataOutputStream(blockingOut), workGroup, bufferSize, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);
            out.start();

            final AtomicInteger messagesSent = new AtomicInteger(0);
            final Thread producer = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    try {
                        out.sendAsync(serializeLong(i));
                        messagesSent.incrementAndGet();
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            producer.setDaemon(true);
            producer.start();

            // Producer must be blocked by backpressure: at most buffer + one in-flight.
            MILLISECONDS.sleep(150);
            final int snapshot = messagesSent.get();
            assertTrue(producer.isAlive(), "Producer thread should be alive");
            // one message polled from queue and blocked on writing to output while bufferSize messages sit in the queue
            assertEquals(bufferSize + 1, snapshot, "producer should be blocked by backpressure");

            // Release the writer; all messages should drain.
            blockingOut.unlock();
            assertEventuallyEquals(count, messagesSent::get, Duration.ofSeconds(5), "all messages should be sent");

            testAndAwaitTermination(workGroup, out::done);
            assertEquals(AsyncOutputStream.Status.DONE, out.getStatus());

            verifyOrderedMessages(count, byteOut.toByteArray());
        }

        @Test
        @DisplayName("Zero-length payload round-trips as an empty length-prefixed frame")
        void zeroLengthPayloadRoundTrips() throws IOException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "zero-len", null);
            final AsyncOutputStream out = newOut(new DataOutputStream(byteOut), workGroup);
            out.start();

            testAndAwaitTermination(workGroup, () -> {
                out.sendAsync(new byte[0]);
                out.done();
            });

            final DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
            assertEquals(0, dataIn.readInt(), "zero-length frame header should be present");
            assertEquals(-1, dataIn.readInt(), "termination marker should follow");
        }

        @Test
        @DisplayName("Large message (1 MiB) round-trips byte-exact")
        void largeMessageRoundTrip() throws IOException {
            final byte[] payload = new byte[1 << 20];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xFF);
            }
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "large-msg", null);
            final AsyncOutputStream out = newOut(new DataOutputStream(byteOut), workGroup);
            out.start();

            testAndAwaitTermination(workGroup, () -> {
                out.sendAsync(payload);
                out.done();
            });

            final DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
            assertEquals(payload.length, dataIn.readInt(), "length header should match");
            final byte[] received = new byte[payload.length];
            dataIn.readFully(received);
            for (int i = 0; i < payload.length; i++) {
                assertEquals(payload[i], received[i], "byte " + i + " must round-trip exactly");
            }
            assertEquals(-1, dataIn.readInt(), "termination marker should follow");
        }

        @Test
        @DisplayName("Buffered writes are flushed within flushInterval without further sends")
        void flushIntervalTriggersFlush() {
            final AtomicInteger flushCount = new AtomicInteger();
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final OutputStream flushCounter = new OutputStream() {
                @Override
                public void write(final int b) {
                    byteOut.write(b);
                }

                @Override
                public void write(final byte[] b, final int off, final int len) {
                    byteOut.write(b, off, len);
                }

                @Override
                public void flush() {
                    flushCount.incrementAndGet();
                }
            };
            final Duration flushInterval = Duration.ofMillis(50);
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "flush-tick", null);
            final AsyncOutputStream out = new AsyncOutputStream(
                    new DataOutputStream(flushCounter), workGroup, DEFAULT_BUFFER_SIZE, flushInterval, DEFAULT_TIMEOUT);
            out.start();

            testAndAwaitTermination(workGroup, () -> {
                out.sendAsync(serializeLong(42));
                // Wait long enough for the writer to wake from poll() and flush at least once.
                assertEventuallyTrue(
                        () -> flushCount.get() >= 1,
                        Duration.ofSeconds(2),
                        "writer should flush within a few flushInterval cycles");

                out.done();
            });
        }

        @Test
        @DisplayName("Periodic flush still fires when the queue is continuously populated")
        void flushHappensUnderSustainedLoad() {
            // Each underlying byte write sleeps briefly, so writeMessage takes longer than the
            // flushInterval. With a constantly populated queue, poll() always returns immediately,
            // so flushing must be driven by the wall-clock time-since-last-flush check, not by
            // the poll timeout. Without that check, only the final shutdown flush would fire.
            final AtomicInteger flushCount = new AtomicInteger();
            final OutputStream slowOut = new OutputStream() {
                @Override
                public void write(final int b) {
                    sleepUninterruptibly(2);
                }

                @Override
                public void write(final byte[] b, final int off, final int len) {
                    sleepUninterruptibly(2);
                }

                @Override
                public void flush() {
                    flushCount.incrementAndGet();
                }
            };
            final Duration flushInterval = Duration.ofMillis(5);
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "flush-busy", null);
            final AsyncOutputStream out = new AsyncOutputStream(
                    new DataOutputStream(slowOut), workGroup, DEFAULT_BUFFER_SIZE, flushInterval, DEFAULT_TIMEOUT);
            out.start();

            testAndAwaitTermination(workGroup, () -> {
                // Keep sending so the queue is never empty for the writer.
                final int count = 50;
                for (int i = 0; i < count; i++) {
                    out.sendAsync(serializeLong(i));
                }

                // Expect periodic flushes during the run — at minimum more than the single final flush.
                assertEventuallyTrue(
                        () -> flushCount.get() >= 3,
                        Duration.ofSeconds(5),
                        "writer should flush periodically under sustained load, observed " + flushCount.get());

                out.done();
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
            final DataOutputStream outputStream = mock(DataOutputStream.class);
            final AsyncOutputStream out = newOut(outputStream, workGroup);

            out.start();
            assertTrue(out.isAlive(), "Stream should be alive");

            assertThrows(IllegalStateException.class, out::start, "Second start should throw an exception");

            verify(workGroup, times(1)).execute(eq("async-output-stream"), any(Runnable.class));
            verifyNoMoreInteractions(workGroup);
        }

        @Test
        @DisplayName("Background thread failed to submit")
        void backgroundWriteThreadFailsToBeSubmitted() {
            final RuntimeException cause = new RuntimeException();
            final StandardWorkGroup workGroup = mock(StandardWorkGroup.class);
            final DataOutputStream outputStream = mock(DataOutputStream.class);

            doThrow(cause).when(workGroup).execute(eq("async-output-stream"), any(Runnable.class));

            final AsyncOutputStream out = newOut(outputStream, workGroup);
            assertThrows(MerkleSynchronizationException.class, out::start);
            assertFalse(out.isAlive(), "Stream should not be alive");
            assertEquals(AsyncOutputStream.Status.DONE, out.getStatus());

            verify(workGroup, times(1)).execute(eq("async-output-stream"), any(Runnable.class));
            verify(workGroup, times(1)).handleError(cause);
            verifyNoMoreInteractions(workGroup);
        }

        @Test
        @DisplayName("sendAsync after done() is rejected (status is FINISHING/DONE)")
        void sendAsyncAfterDoneIsRejected() throws InterruptedException {
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final BlockingOutputStream blockingOut = new BlockingOutputStream(byteOut);
            // Hold the writer in the middle of a write so we can observe FINISHING.
            blockingOut.lock();

            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "send-after-done", null);
            final AsyncOutputStream out = newOut(new DataOutputStream(blockingOut), workGroup);
            out.start();

            out.sendAsync(serializeLong(1));
            // Give the writer thread a chance to dequeue and block on the locked stream.
            MILLISECONDS.sleep(50);

            out.done();
            assertEquals(
                    AsyncOutputStream.Status.FINISHING,
                    out.getStatus(),
                    "done() should flip status to FINISHING while writer is still draining");
            assertTrue(out.isAlive(), "isAlive should still be true during FINISHING");

            assertThrows(
                    IllegalStateException.class,
                    () -> out.sendAsync(serializeLong(2)),
                    "sendAsync during FINISHING must be rejected");

            blockingOut.unlock();
            workGroup.waitForTermination();
            assertEquals(AsyncOutputStream.Status.DONE, out.getStatus());
            assertFalse(out.isAlive());

            assertThrows(
                    IllegalStateException.class,
                    () -> out.sendAsync(serializeLong(3)),
                    "sendAsync after DONE must also be rejected");
        }

        @Test
        @DisplayName("sendAsync times out with MerkleSynchronizationException when buffer stays full")
        void sendAsyncTimesOutWhenQueueFull() throws InterruptedException {
            final int bufferSize = 5;
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final BlockingOutputStream blockingOut = new BlockingOutputStream(byteOut);
            blockingOut.lock();

            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "queue-timeout", null);
            final AsyncOutputStream out = new AsyncOutputStream(
                    new DataOutputStream(blockingOut),
                    workGroup,
                    bufferSize,
                    Duration.ofMillis(10),
                    Duration.ofMillis(100));
            out.start();

            int sent = 0;
            MerkleSynchronizationException caught = null;
            try {
                for (int i = 0; i < bufferSize * 3; i++) {
                    out.sendAsync(serializeLong(i));
                    sent++;
                }
            } catch (final MerkleSynchronizationException e) {
                caught = e;
            }

            assertNotNull(caught, "sendAsync should have timed out once the buffer filled up");
            assertTrue(
                    caught.getMessage().contains("Timed out waiting to send data"),
                    "exception message should mention the timeout, was: " + caught.getMessage());
            assertTrue(sent >= bufferSize, "expected at least bufferSize sends to succeed before the timeout");

            blockingOut.unlock();
            out.done();
            workGroup.waitForTermination();
        }

        @Test
        @DisplayName("Writer I/O error is forwarded to the work group")
        void writerIoErrorPropagatesToWorkGroup() throws InterruptedException {
            final OutputStream brokenOut = new OutputStream() {
                @Override
                public void write(final int b) throws IOException {
                    throw new IOException("simulated write failure");
                }
            };
            final ExceptionCapture exceptionCapture = new ExceptionCapture();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "writer-io-error", null, exceptionCapture);
            final AsyncOutputStream out = newOut(new DataOutputStream(brokenOut), workGroup);
            out.start();
            out.sendAsync(serializeLong(1));

            assertEventuallyTrue(
                    workGroup::hasExceptions,
                    Duration.ofSeconds(5),
                    "work group should record the writer's IOException");

            out.done();
            workGroup.waitForTermination();

            assertEquals(AsyncOutputStream.Status.DONE, out.getStatus());
            assertEquals(1, exceptionCapture.getExceptions().size());
            assertInstanceOf(IOException.class, exceptionCapture.getExceptions().peek());
        }
    }

    /**
     * Tests for concurrent usage from multiple producer threads.
     */
    @Nested
    class Concurrent {

        @Test
        @DisplayName("Concurrent starts: only one succeeds")
        void concurrentStartsThrowsAnException() throws InterruptedException {
            final StandardWorkGroup workGroup = mock(StandardWorkGroup.class);
            final DataOutputStream outputStream = mock(DataOutputStream.class);
            final AsyncOutputStream out = newOut(outputStream, workGroup);

            final int threadsCount = 10;
            final AtomicInteger exceptionsCount = new AtomicInteger();
            final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
            final CountDownLatch doneLatch = new CountDownLatch(threadsCount);

            for (int i = 0; i < threadsCount; i++) {
                new Thread(() -> {
                            try {
                                barrier.await();
                                out.start();
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

            if (!doneLatch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Not all starter threads finished");
            }
            assertEquals(threadsCount - 1, exceptionsCount.get(), "Only one thread can start, others should throw");
            verify(workGroup, times(1)).execute(eq("async-output-stream"), any(Runnable.class));
            verifyNoMoreInteractions(workGroup);
        }

        @Test
        @DisplayName("Concurrent sendAsync from many producers preserves per-producer order")
        void concurrentSendPreservesPerProducerOrder() throws IOException {
            final int producers = 8;
            final int perProducer = 200;
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "concurrent-send", null);
            final AsyncOutputStream out = new AsyncOutputStream(
                    new DataOutputStream(byteOut),
                    workGroup,
                    DEFAULT_BUFFER_SIZE,
                    DEFAULT_FLUSH_INTERVAL,
                    DEFAULT_TIMEOUT);
            out.start();

            final CyclicBarrier barrier = new CyclicBarrier(producers);
            final CountDownLatch doneLatch = new CountDownLatch(producers);

            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                final Thread t = new Thread(() -> {
                    try {
                        barrier.await();
                        for (int seq = 0; seq < perProducer; seq++) {
                            out.sendAsync(encodeProducerMsg(producerId, seq));
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
                t.setDaemon(true);
                t.start();
            }

            testAndAwaitTermination(workGroup, () -> {
                if (!doneLatch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Not all producer threads finished");
                }
                out.done();
            });

            final Map<Integer, List<Integer>> perProducerSeqs = new HashMap<>();
            final DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
            int total = 0;
            while (true) {
                final int len = dataIn.readInt();
                if (len < 0) {
                    break;
                }
                assertEquals(Long.BYTES, len, "every framed message should be a long");
                final long combined = dataIn.readLong();
                final int producerId = (int) (combined >>> 32);
                final int seq = (int) combined;
                perProducerSeqs
                        .computeIfAbsent(producerId, k -> new ArrayList<>())
                        .add(seq);
                total++;
            }
            assertEquals(producers * perProducer, total, "every message should have been written");

            for (int p = 0; p < producers; p++) {
                final List<Integer> seqs = perProducerSeqs.get(p);
                assertNotNull(seqs, "producer " + p + " should have at least one message");
                assertEquals(perProducer, seqs.size(), "producer " + p + " should have all its messages");
                for (int i = 0; i < seqs.size(); i++) {
                    assertEquals(
                            i, seqs.get(i).intValue(), "producer " + p + " message at index " + i + " out of order");
                }
            }
        }

        @Test
        @DisplayName("sendAsync interrupted while waiting on a full buffer throws InterruptedException")
        void sendAsyncInterruptedOnFullBuffer() throws InterruptedException {
            final int bufferSize = 2;
            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            final BlockingOutputStream blockingOut = new BlockingOutputStream(byteOut);
            blockingOut.lock();

            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "send-interrupt", null);
            final AsyncOutputStream out = new AsyncOutputStream(
                    new DataOutputStream(blockingOut),
                    workGroup,
                    bufferSize,
                    DEFAULT_FLUSH_INTERVAL,
                    Duration.ofSeconds(60));
            out.start();

            // Let the writer pick up the first message and block on the locked stream.
            out.sendAsync(serializeLong(0));
            MILLISECONDS.sleep(50);
            // Fill the buffer.
            out.sendAsync(serializeLong(1));
            out.sendAsync(serializeLong(2));

            final AtomicReference<Object> outcome = new AtomicReference<>();
            final CountDownLatch startedLatch = new CountDownLatch(1);
            final Thread caller = new Thread(() -> {
                startedLatch.countDown();
                try {
                    out.sendAsync(serializeLong(3));
                    outcome.set("unexpected-success");
                } catch (final InterruptedException e) {
                    outcome.set(e);
                    Thread.currentThread().interrupt();
                } catch (final Throwable t) {
                    outcome.set(t);
                }
            });
            caller.setDaemon(true);
            caller.start();

            assertTrue(startedLatch.await(2, TimeUnit.SECONDS), "caller thread should have started");
            // Give the caller a moment to enter offer(timeout).
            MILLISECONDS.sleep(50);
            caller.interrupt();
            caller.join(2_000);

            assertFalse(caller.isAlive(), "caller thread should exit after interrupt");
            assertInstanceOf(
                    InterruptedException.class,
                    outcome.get(),
                    "sendAsync should throw InterruptedException, got: " + outcome.get());

            // Cleanup: unblock writer, signal done, drain.
            blockingOut.unlock();
            out.done();
            workGroup.waitForTermination();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static AsyncOutputStream newOut(final DataOutputStream out, final StandardWorkGroup workGroup) {
        return new AsyncOutputStream(out, workGroup, DEFAULT_BUFFER_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);
    }

    private void verifyOrderedMessages(int messagesCount, byte[] data) throws IOException {
        final DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(data));
        for (int i = 0; i < messagesCount; i++) {
            assertEquals(Long.BYTES, dataIn.readInt(), "message length should be Long.BYTES");
            assertEquals(i, dataIn.readLong(), "messages should be drained in send order");
        }
        assertEquals(-1, dataIn.readInt(), "termination marker should follow all messages");
        assertEquals(0, dataIn.available(), "no bytes after the termination marker");
    }

    private static byte[] serializeLong(final long value) {
        final byte[] bytes = new byte[Long.BYTES];
        BufferedData.wrap(bytes).writeLong(value);
        return bytes;
    }

    private static byte[] encodeProducerMsg(final int producerId, final int seq) {
        return serializeLong(((long) producerId << 32) | (seq & 0xFFFFFFFFL));
    }

    private static void sleepUninterruptibly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void testAndAwaitTermination(final StandardWorkGroup workGroup, final ThrowingRunnable test) {
        try {
            test.run();
            workGroup.waitForTermination();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", ex);
        } catch (Exception ex) {
            workGroup.handleError(ex); // cancel submitted tasks if the test body threw
            throw new RuntimeException(ex);
        } catch (Throwable ex) {
            workGroup.handleError(ex);
            throw ex;
        }
    }
}
