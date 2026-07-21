// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import org.junit.jupiter.api.Test;

class ObservedSocketOutputStreamTest {

    @Test
    void splitsCallerWriteIntoBoundedPreWriteReservationsWithoutCopyingPayload() throws Exception {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(3, time);
        final RecordingOutputStream raw = new RecordingOutputStream(controller, time);
        final ObservedSocketOutputStream observed = new ObservedSocketOutputStream(raw, controller, controller::abort);
        final byte[] bytes = {9, 0, 1, 2, 3, 4, 5, 6, 8};

        observed.write(bytes, 1, 7);

        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6}, raw.toByteArray());
        assertEquals(List.of(3, 3, 1), raw.writeLengths);
        assertEquals(List.of(3L, 6L, 7L), raw.observedBytesAtRawWrite);
        assertEquals(3, raw.arrayReferences.size());
        raw.arrayReferences.forEach(reference -> assertSame(bytes, reference));

        final SocketVisibilityStats stats = controller.stats();
        assertEquals(7, stats.observedBytes());
        assertEquals(3, stats.rangeCount());
        assertEquals(3, stats.maxRangeSizeBytes());
        assertEquals(3, stats.rawWriteCount());
        assertEquals(51, stats.rawWriteDurationNanos());
        assertEquals(17, stats.maxRawWriteDurationNanos());
        assertEquals(0, stats.failedRawWrites());
    }

    @Test
    void singleByteWritePublishesMetadataBeforeDelegating() throws Exception {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(8, time);
        final RecordingOutputStream raw = new RecordingOutputStream(controller, time);
        final ObservedSocketOutputStream observed = new ObservedSocketOutputStream(raw, controller, controller::abort);

        observed.write(0x1ff);

        assertArrayEquals(new byte[] {(byte) 0xff}, raw.toByteArray());
        assertEquals(List.of(1L), raw.observedBytesAtRawWrite);
        assertEquals(1, controller.stats().rangeCount());
    }

    @Test
    void zeroLengthAndInvalidRangesFollowOutputStreamContract() throws Exception {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(8, time);
        final RecordingOutputStream raw = new RecordingOutputStream(controller, time);
        final ObservedSocketOutputStream observed = new ObservedSocketOutputStream(raw, controller, controller::abort);

        observed.write(new byte[1], 0, 0);

        assertEquals(0, raw.writeLengths.size());
        assertEquals(0, controller.stats().rangeCount());
        assertThrows(IndexOutOfBoundsException.class, () -> observed.write(new byte[2], 1, 2));
    }

    @Test
    void rawWriteFailureIsRecordedAndAbortsConnectionAfterDelegation() {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(8, time);
        final IOException expected = new IOException("raw write failed");
        final AtomicInteger aborts = new AtomicInteger();
        final OutputStream raw = new OutputStream() {
            @Override
            public void write(final int value) throws IOException {
                time.advance(29);
                throw expected;
            }
        };
        final ObservedSocketOutputStream observed = new ObservedSocketOutputStream(raw, controller, failure -> {
            aborts.incrementAndGet();
            controller.abort(failure);
        });

        final IOException actual = assertThrows(IOException.class, () -> observed.write(1));

        assertSame(expected, actual);
        assertEquals(1, aborts.get());
        assertEquals(1, controller.stats().failedRawWrites());
        assertEquals(29, controller.stats().rawWriteDurationNanos());
        assertTrue(controller.stats().state().startsWith("ABORTED"));
    }

    @Test
    void flushFailureUsesTheConnectionAbortPath() {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(8, time);
        final AtomicInteger aborts = new AtomicInteger();
        final OutputStream raw = new OutputStream() {
            @Override
            public void write(final int value) {}

            @Override
            public void flush() throws IOException {
                throw new IOException("flush failed");
            }
        };
        final ObservedSocketOutputStream observed =
                new ObservedSocketOutputStream(raw, controller, failure -> aborts.incrementAndGet());

        assertThrows(IOException.class, observed::flush);
        assertEquals(1, aborts.get());
    }

    @Test
    void closeBeginsControllerCleanupAndIsIdempotent() throws Exception {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(8, time);
        final AtomicInteger rawCloses = new AtomicInteger();
        final OutputStream raw = new OutputStream() {
            @Override
            public void write(final int value) {}

            @Override
            public void close() {
                assertEquals("CLOSED", controller.stats().state());
                rawCloses.incrementAndGet();
            }
        };
        final ObservedSocketOutputStream observed = new ObservedSocketOutputStream(raw, controller, controller::abort);

        observed.close();
        observed.close();

        assertEquals(1, rawCloses.get());
        assertEquals("CLOSED", controller.stats().state());
        assertThrows(IOException.class, () -> observed.write(1));
    }

    @Test
    void closeBypassesWriterLockAndWakesBlockedRawWrite() throws Exception {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController controller = controller(8, time);
        final BlockingOutputStream raw = new BlockingOutputStream();
        final ObservedSocketOutputStream observed = new ObservedSocketOutputStream(raw, controller, controller::abort);
        final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        final Thread writer = new Thread(() -> {
            try {
                observed.write(1);
            } catch (final Throwable failure) {
                writerFailure.set(failure);
            }
        });
        writer.setDaemon(true);
        writer.start();
        assertTrue(raw.entered.await(1, TimeUnit.SECONDS));

        observed.close();
        writer.join(1_000);

        assertFalse(writer.isAlive(), "close must not wait for the writer lifecycle lock");
        assertTrue(writerFailure.get() instanceof IOException);
        assertEquals(1, raw.closeCount.get());
        assertEquals(1, controller.stats().failedRawWrites());
    }

    @Test
    void simultaneousDirectionalWriteFailuresAbortBothControllersWithoutLockInversion() throws Exception {
        final ManualTime time = new ManualTime();
        final SocketVisibilityController firstController = controller(8, time);
        final SocketVisibilityController secondController = controller(8, time);
        final AtomicBoolean aborted = new AtomicBoolean();
        final CyclicBarrier bothWriting = new CyclicBarrier(2);
        final SocketVisibilityController.AbortHandler connectionAbort = failure -> {
            if (aborted.compareAndSet(false, true)) {
                firstController.abort(failure);
                secondController.abort(failure);
            }
        };
        final ObservedSocketOutputStream first = new ObservedSocketOutputStream(
                simultaneousFailureStream(bothWriting), firstController, connectionAbort);
        final ObservedSocketOutputStream second = new ObservedSocketOutputStream(
                simultaneousFailureStream(bothWriting), secondController, connectionAbort);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        final Thread firstWriter = failingWriter(first, firstFailure);
        final Thread secondWriter = failingWriter(second, secondFailure);

        firstWriter.start();
        secondWriter.start();
        firstWriter.join(1_000);
        secondWriter.join(1_000);

        assertFalse(firstWriter.isAlive());
        assertFalse(secondWriter.isAlive());
        assertTrue(firstFailure.get() instanceof IOException);
        assertTrue(secondFailure.get() instanceof IOException);
        assertTrue(firstController.stats().state().startsWith("ABORTED"));
        assertTrue(secondController.stats().state().startsWith("ABORTED"));
    }

    private static OutputStream simultaneousFailureStream(final CyclicBarrier bothWriting) {
        return new OutputStream() {
            @Override
            public void write(final int value) throws IOException {
                try {
                    bothWriting.await(1, TimeUnit.SECONDS);
                } catch (final Exception e) {
                    throw new IOException("test writers did not rendezvous", e);
                }
                throw new IOException("simultaneous raw write failure");
            }
        };
    }

    private static Thread failingWriter(
            final ObservedSocketOutputStream output, final AtomicReference<Throwable> failure) {
        final Thread writer = new Thread(() -> {
            try {
                output.write(1);
            } catch (final Throwable throwable) {
                failure.set(throwable);
            }
        });
        writer.setDaemon(true);
        return writer;
    }

    private static SocketVisibilityController controller(final int maxRangeBytes, final ManualTime time) {
        return new SocketVisibilityController(0, Long.MAX_VALUE, 1, maxRangeBytes, 100, 1_000_000, time, time);
    }

    private static final class ManualTime
            implements SocketVisibilityController.NanoClock, SocketVisibilityController.ConditionAwaiter {
        private long now;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public long awaitNanos(final Condition condition, final long nanos) {
            now += nanos;
            return 0;
        }

        private void advance(final long nanos) {
            now += nanos;
        }
    }

    private static final class RecordingOutputStream extends OutputStream {
        private final SocketVisibilityController controller;
        private final ManualTime time;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final List<Integer> writeLengths = new ArrayList<>();
        private final List<Long> observedBytesAtRawWrite = new ArrayList<>();
        private final List<byte[]> arrayReferences = new ArrayList<>();

        private RecordingOutputStream(final SocketVisibilityController controller, final ManualTime time) {
            this.controller = controller;
            this.time = time;
        }

        @Override
        public void write(final int value) {
            observedBytesAtRawWrite.add(controller.stats().observedBytes());
            writeLengths.add(1);
            time.advance(17);
            delegate.write(value);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) {
            observedBytesAtRawWrite.add(controller.stats().observedBytes());
            writeLengths.add(length);
            arrayReferences.add(bytes);
            time.advance(17);
            delegate.write(bytes, offset, length);
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void write(final int value) throws IOException {
            entered.countDown();
            try {
                if (!closed.await(1, TimeUnit.SECONDS)) {
                    throw new IOException("test timed out waiting for close");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closed.countDown();
        }
    }
}
