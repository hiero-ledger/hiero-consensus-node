// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import org.junit.jupiter.api.Test;

class ScheduledSocketInputStreamTest {

    @Test
    void doesNotTouchRawInputUntilSenderRelativeDeadline() throws Exception {
        final ManualTime time = new ManualTime(1_000);
        final SocketVisibilityController controller = controller(100, Long.MAX_VALUE, 8, time);
        controller.reserveRange(3);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {1, 2, 3}, time);
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> {});

        final byte[] destination = new byte[3];
        assertEquals(3, scheduled.read(destination));

        assertEquals(List.of(1_100L), raw.rawReadTimes);
        assertEquals(List.of(3), raw.requestedLengths);
        assertEquals(3, controller.stats().returnedBytes());
    }

    @Test
    void clampsRawReadsAndConsumesOnlyTheActuallyReturnedPrefix() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        controller.reserveRange(5);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {1, 2, 3, 4, 5}, time);
        raw.maxBytesPerRead = 2;
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> {});
        final byte[] destination = new byte[10];

        assertEquals(2, scheduled.read(destination));
        assertEquals(3, controller.stats().pendingBytes());
        assertEquals(2, scheduled.read(destination, 2, 8));
        assertEquals(1, controller.stats().pendingBytes());
        assertEquals(1, scheduled.read(destination, 4, 6));

        assertEquals(List.of(5, 3, 1), raw.requestedLengths);
        assertEquals(5, controller.stats().returnedBytes());
        assertEquals(0, controller.stats().pendingBytes());
    }

    @Test
    void zeroReturningDelegateIsRetriedWithoutConsumingAllowance() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        controller.reserveRange(1);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {9}, time);
        raw.returnZeroOnce = true;
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> {});

        assertEquals(9, scheduled.read());

        assertEquals(2, raw.requestedLengths.size());
        assertEquals(1, controller.stats().rawReadCount());
        assertEquals(1, controller.stats().returnedBytes());
    }

    @Test
    void skipConsumesThroughTheGateAndUsesABoundedScratchBuffer() throws Exception {
        final ManualTime time = new ManualTime(0);
        final byte[] payload = new byte[9_000];
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, payload.length, time);
        controller.reserveRange(payload.length);
        final RecordingInputStream raw = new RecordingInputStream(payload, time);
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> {});

        assertEquals(8 * 1_024, scheduled.skip(payload.length));
        assertEquals(List.of(8 * 1_024), raw.requestedLengths);
        assertEquals(8 * 1_024, controller.stats().returnedBytes());
        assertEquals(payload.length - 8 * 1_024, controller.stats().pendingBytes());
        assertEquals(0, scheduled.skip(0));
        assertEquals(0, scheduled.skip(-1));
    }

    @Test
    void availableIsIntersectionOfRawAvailabilityAndEligiblePrefix() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(100, Long.MAX_VALUE, 8, time);
        controller.reserveRange(2);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {1, 2, 3, 4}, time);
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> {});

        assertEquals(0, scheduled.available());
        time.set(99);
        assertEquals(0, scheduled.available());
        time.set(100);
        assertEquals(2, scheduled.available());
    }

    @Test
    void oneLogicalTimeoutIncludesMetadataAndEligibilityWait() {
        final ManualTime time = new ManualTime(500);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {1}, time);
        final List<Integer> socketTimeouts = new ArrayList<>();
        final AtomicInteger aborts = new AtomicInteger();
        final ScheduledSocketInputStream scheduled = new ScheduledSocketInputStream(
                raw, socketTimeouts::add, 5, controller, failure -> aborts.incrementAndGet());

        final IOException failure = assertThrows(IOException.class, scheduled::read);

        assertInstanceOf(SocketTimeoutException.class, failure);
        assertEquals(5_000_500, time.nanoTime());
        assertTrue(raw.requestedLengths.isEmpty());
        assertTrue(socketTimeouts.isEmpty());
        assertEquals(1, aborts.get());
    }

    @Test
    void rawSocketTimeoutIsClampedToRemainingLogicalDeadlineAndRestored() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(3_200_000, Long.MAX_VALUE, 8, time);
        controller.reserveRange(1);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {7}, time);
        raw.rawReadDurationNanos = 1_000_000;
        final List<Integer> socketTimeouts = new ArrayList<>();
        final ScheduledSocketInputStream scheduled =
                new ScheduledSocketInputStream(raw, socketTimeouts::add, 10, controller, failure -> {});

        assertEquals(7, scheduled.read());

        assertEquals(List.of(7, 10), socketTimeouts);
        assertEquals(1_000_000, controller.stats().rawReadWaitNanos());
    }

    @Test
    void rawFailureIsRecordedAndUsesConnectionAbortPath() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(3_200_000, Long.MAX_VALUE, 8, time);
        controller.reserveRange(1);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {1}, time);
        raw.rawReadDurationNanos = 500_000;
        raw.failure = new IOException("raw read failed");
        final List<Integer> socketTimeouts = new ArrayList<>();
        final AtomicInteger aborts = new AtomicInteger();
        final ScheduledSocketInputStream scheduled =
                new ScheduledSocketInputStream(raw, socketTimeouts::add, 10, controller, failure -> {
                    aborts.incrementAndGet();
                    controller.abort(failure);
                });

        final IOException failure = assertThrows(IOException.class, scheduled::read);

        assertEquals("raw read failed", failure.getMessage());
        assertEquals(List.of(7, 10), socketTimeouts);
        assertEquals(1, controller.stats().failedRawReads());
        assertEquals(500_000, controller.stats().rawReadWaitNanos());
        assertEquals(1, aborts.get());
        assertTrue(controller.stats().state().startsWith("ABORTED"));
    }

    @Test
    void unexpectedRawEofIsAConnectionFailure() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        controller.reserveRange(1);
        final AtomicInteger aborts = new AtomicInteger();
        final ScheduledSocketInputStream scheduled =
                scheduled(new ByteArrayInputStream(new byte[0]), 0, controller, failure -> aborts.incrementAndGet());

        final IOException failure = assertThrows(IOException.class, scheduled::read);

        assertTrue(failure.getMessage().contains("Unexpected socket EOF"));
        assertEquals(1, controller.stats().failedRawReads());
        assertEquals(1, aborts.get());
    }

    @Test
    void availableFailureUsesConnectionAbortPath() {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        final AtomicInteger aborts = new AtomicInteger();
        final InputStream raw = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int available() throws IOException {
                throw new IOException("available failed");
            }
        };
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> aborts.incrementAndGet());

        assertThrows(IOException.class, scheduled::available);
        assertEquals(1, aborts.get());
    }

    @Test
    void closeIsIdempotentDisablesControllerAndWakesBlockedRawRead() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        controller.reserveRange(1);
        final BlockingInputStream raw = new BlockingInputStream();
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, controller::abort);
        final AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        final Thread reader = new Thread(() -> {
            try {
                scheduled.read();
            } catch (final Throwable failure) {
                readerFailure.set(failure);
            }
        });
        reader.setDaemon(true);
        reader.start();
        assertTrue(raw.entered.await(1, TimeUnit.SECONDS));

        scheduled.close();
        scheduled.close();
        reader.join(1_000);

        assertFalse(reader.isAlive());
        assertInstanceOf(IOException.class, readerFailure.get());
        assertEquals(1, raw.closeCount.get());
        assertThrows(IOException.class, () -> controller.reserveRange(1));
    }

    @Test
    void markResetAndZeroLengthContractsCannotBypassScheduling() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 8, time);
        final RecordingInputStream raw = new RecordingInputStream(new byte[] {1}, time);
        final ScheduledSocketInputStream scheduled = scheduled(raw, 0, controller, failure -> {});

        assertEquals(0, scheduled.read(new byte[1], 0, 0));
        assertFalse(scheduled.markSupported());
        scheduled.mark(100);
        assertThrows(IOException.class, scheduled::reset);
        assertTrue(raw.requestedLengths.isEmpty());
    }

    private static ScheduledSocketInputStream scheduled(
            final InputStream raw,
            final int timeoutMillis,
            final SocketVisibilityController controller,
            final SocketVisibilityController.AbortHandler abortHandler) {
        return new ScheduledSocketInputStream(raw, ignored -> {}, timeoutMillis, controller, abortHandler);
    }

    private static SocketVisibilityController controller(
            final long latencyNanos,
            final long bandwidthBytesPerSecond,
            final int maxRangeBytes,
            final ManualTime time) {
        return new SocketVisibilityController(
                latencyNanos, bandwidthBytesPerSecond, 1, maxRangeBytes, 100, 1_000_000, time, time);
    }

    private static final class ManualTime
            implements SocketVisibilityController.NanoClock, SocketVisibilityController.ConditionAwaiter {
        private long now;

        private ManualTime(final long initialNanos) {
            now = initialNanos;
        }

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

        private void set(final long nanos) {
            now = nanos;
        }
    }

    private static final class RecordingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private final ManualTime time;
        private final List<Integer> requestedLengths = new ArrayList<>();
        private final List<Long> rawReadTimes = new ArrayList<>();
        private int maxBytesPerRead = Integer.MAX_VALUE;
        private long rawReadDurationNanos;
        private boolean returnZeroOnce;
        private IOException failure;

        private RecordingInputStream(final byte[] bytes, final ManualTime time) {
            delegate = new ByteArrayInputStream(bytes);
            this.time = time;
        }

        @Override
        public int read() {
            throw new AssertionError("scheduled stream must use the bounded array read");
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            requestedLengths.add(length);
            rawReadTimes.add(time.nanoTime());
            time.advance(rawReadDurationNanos);
            if (failure != null) {
                throw failure;
            }
            if (returnZeroOnce) {
                returnZeroOnce = false;
                return 0;
            }
            return delegate.read(bytes, offset, Math.min(length, maxBytesPerRead));
        }

        @Override
        public int available() {
            return delegate.available();
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public int read() {
            throw new AssertionError("scheduled stream must use the bounded array read");
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
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
