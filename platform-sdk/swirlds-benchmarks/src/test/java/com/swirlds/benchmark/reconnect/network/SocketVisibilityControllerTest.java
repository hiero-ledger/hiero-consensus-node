// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.Condition;
import org.junit.jupiter.api.Test;

class SocketVisibilityControllerTest {

    @Test
    void exactScheduleCombinesSenderRelativeLatencyAndSerialization() throws Exception {
        final ManualTime time = new ManualTime(1_000);
        final SocketVisibilityController controller = controller(100, 1_000_000_000L, 10, 100, time);

        final SocketVisibilityController.Reservation reservation = controller.reserveRange(3);
        assertEquals(0, reservation.startOffset());
        assertEquals(3, reservation.byteCount());
        assertEquals(1_000, reservation.observedAtNanos());
        assertEquals(1_000, reservation.serializationStartNanos());
        assertEquals(1_003, reservation.serializationEndNanos());
        assertEquals(3, reservation.serializationDurationNanos());
        assertEquals(0, controller.eligibleBytesNow(3));

        final SocketVisibilityController.ReadAllowance allowance = controller.awaitReadable(3, 0, false);
        assertEquals(3, allowance.byteCount());
        assertEquals(1_103, allowance.eligibleAtNanos());
        assertEquals(1_103, time.nanoTime());

        controller.consume(allowance, 3, 7);
        final SocketVisibilityStats stats = controller.stats();
        assertEquals(3, stats.observedBytes());
        assertEquals(3, stats.returnedBytes());
        assertEquals(100, stats.latencyWaitNanos());
        assertEquals(3, stats.bandwidthWaitNanos());
        assertEquals(0, stats.pendingBytes());
        assertEquals(1, stats.rawReadCount());
        assertEquals(7, stats.rawReadWaitNanos());
        assertEquals(1, stats.observerToFirstReturnSamples());
        assertEquals(103, stats.maxObserverToFirstReturnNanos());
    }

    @Test
    void fractionalSerializationCarryDoesNotLoseSubNanosecondRemainders() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, 3, 1, 1, time);

        final SocketVisibilityController.Reservation first = controller.reserveRange(1);
        final SocketVisibilityController.Reservation second = controller.reserveRange(1);
        final SocketVisibilityController.Reservation third = controller.reserveRange(1);

        assertEquals(0, first.serializationStartNanos());
        assertEquals(333_333_334, first.serializationEndNanos());
        assertEquals(333_333_334, second.serializationStartNanos());
        assertEquals(666_666_667, second.serializationEndNanos());
        assertEquals(666_666_667, third.serializationStartNanos());
        assertEquals(1_000_000_000, third.serializationEndNanos());

        final SocketVisibilityController.ReadAllowance firstAllowance = controller.awaitReadable(3, 0, false);
        assertEquals(1, firstAllowance.byteCount());
        assertEquals(333_333_334, firstAllowance.eligibleAtNanos());
        controller.consume(firstAllowance, 1, 0);
        final SocketVisibilityController.ReadAllowance secondAllowance = controller.awaitReadable(2, 0, false);
        assertEquals(1, secondAllowance.byteCount());
        assertEquals(666_666_667, secondAllowance.eligibleAtNanos());
        controller.consume(secondAllowance, 1, 0);
        final SocketVisibilityController.ReadAllowance thirdAllowance = controller.awaitReadable(1, 0, false);
        assertEquals(1, thirdAllowance.byteCount());
        assertEquals(1_000_000_000, thirdAllowance.eligibleAtNanos());
        assertEquals(1_000_000_000, time.nanoTime());
    }

    @Test
    void idleSerializationCursorRestartsAtTheNewObservationTime() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, 1, 1, 1, time);

        controller.reserveRange(1);
        final SocketVisibilityController.ReadAllowance first = controller.awaitReadable(1, 0, false);
        controller.consume(first, 1, 0);
        time.set(5_000_000_000L);

        final SocketVisibilityController.Reservation afterIdle = controller.reserveRange(1);
        assertEquals(5_000_000_000L, afterIdle.serializationStartNanos());
        assertEquals(6_000_000_000L, afterIdle.serializationEndNanos());
    }

    @Test
    void nanoTimeWrapIsHandledByDifferenceBasedDeadlineChecks() throws Exception {
        final long start = Long.MAX_VALUE - 10;
        final ManualTime time = new ManualTime(start);
        final SocketVisibilityController controller = controller(20, Long.MAX_VALUE, 5, 8, time);

        controller.reserveRange(1);
        final SocketVisibilityController.ReadAllowance allowance = controller.awaitReadable(1, 0, false);

        assertEquals(start + 20, allowance.eligibleAtNanos());
        assertEquals(start + 20, time.nanoTime());
        assertEquals(1, allowance.byteCount());
    }

    @Test
    void observedRangesAreBoundedAndOffsetsRemainContiguous() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 1, 3, time);

        final SocketVisibilityController.Reservation first = controller.reserveRange(8);
        final SocketVisibilityController.Reservation second = controller.reserveRange(5);
        final SocketVisibilityController.Reservation third = controller.reserveRange(2);

        assertEquals(3, first.byteCount());
        assertEquals(0, first.startOffset());
        assertEquals(3, second.byteCount());
        assertEquals(3, second.startOffset());
        assertEquals(2, third.byteCount());
        assertEquals(6, third.startOffset());
    }

    @Test
    void metadataHighWaterFailureIsConnectionWideAndSticky() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller =
                new SocketVisibilityController(0, Long.MAX_VALUE, 1, 10, 1, 10, time, time);

        controller.reserveRange(1);
        final IOException failure = assertThrows(IOException.class, () -> controller.reserveRange(1));

        assertTrue(failure.getMessage().contains("high-water"));
        assertTrue(controller.stats().state().startsWith("ABORTED"));
        assertThrows(IOException.class, () -> controller.reserveRange(1));
        assertEquals(0, controller.eligibleBytesNow(1));
    }

    @Test
    void logicalDeadlineAbortsWhenNoSenderMetadataArrives() {
        final ManualTime time = new ManualTime(500);
        final SocketVisibilityController controller =
                new SocketVisibilityController(0, Long.MAX_VALUE, 1, 10, 10, 100, time, time);

        final IOException failure = assertThrows(IOException.class, () -> controller.awaitReadable(1, 750, true));

        assertInstanceOf(SocketTimeoutException.class, failure);
        assertEquals(750, time.nanoTime());
        assertTrue(controller.stats().state().startsWith("ABORTED"));
    }

    @Test
    void twoDirectionsKeepIndependentSerializationCursors() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController slowDirection = controller(0, 1, 1, 100, time);
        final SocketVisibilityController fastDirection = controller(0, 1_000_000_000L, 1, 100, time);

        final SocketVisibilityController.Reservation slow = slowDirection.reserveRange(100);
        final SocketVisibilityController.Reservation fast = fastDirection.reserveRange(1);

        assertEquals(100_000_000_000L, slow.serializationEndNanos());
        assertEquals(1, fast.serializationEndNanos());
        assertEquals(1, fastDirection.awaitReadable(1, 0, false).byteCount());
        assertEquals(1, time.nanoTime());
        assertEquals(0, slowDirection.eligibleBytesNow(100));
    }

    @Test
    void cleanupMakesFutureWaitsAndReservationsFail() {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 1, 8, time);

        controller.beginCleanup();

        assertThrows(IOException.class, () -> controller.reserveRange(1));
        assertThrows(IOException.class, () -> controller.awaitReadable(1, 0, false));
        assertEquals("CLOSED", controller.stats().state());
    }

    @Test
    void serializationBacklogExcludesEligibleApplicationUndrainedBytes() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, 1_000_000_000L, 10, 10, time);

        controller.reserveRange(5);
        time.set(100);
        controller.reserveRange(1);

        final SocketVisibilityStats stats = controller.stats();
        assertEquals(6, stats.maxPendingBytes());
        assertEquals(5, stats.maxSerializationBacklogBytes());
        assertEquals(5, stats.maxSerializationBacklogNanos());
    }

    @Test
    void instrumentedControlRetainsTargetWriteDurationThresholds() throws Exception {
        final SocketNetworkConfig config = SocketNetworkConfig.resolve(NetworkProfile.INSTRUMENTED_LOOPBACK, 270, 200);
        final SocketVisibilityController controller = new SocketVisibilityController(config);
        final SocketVisibilityController.Reservation reservation = controller.reserveRange(675);

        controller.recordRawWrite(reservation, 100_000, true);

        final SocketVisibilityStats stats = controller.stats();
        assertEquals(270_000, stats.configuredLatencyNanos());
        assertEquals(25_000_000, stats.configuredBandwidthBytesPerSecond());
        assertEquals(0, stats.modeledLatencyNanos());
        assertEquals(Long.MAX_VALUE, stats.modeledBandwidthBytesPerSecond());
        assertEquals(675, stats.rawWriteBytesOverQuarterLatency());
        assertEquals(675, stats.rawWriteBytesOverSerializationDuration());
    }

    @Test
    void abortRejectsAnAlreadyReadButNotYetConsumedAllowance() throws Exception {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller = controller(0, Long.MAX_VALUE, 1, 8, time);
        controller.reserveRange(1);
        final SocketVisibilityController.ReadAllowance allowance = controller.awaitReadable(1, 0, false);
        final IOException abort = new IOException("abort");

        controller.abort(abort);

        assertSame(abort, assertThrows(IOException.class, () -> controller.consume(allowance, 1, 0)));
        assertEquals(0, controller.stats().returnedBytes());
    }

    @Test
    void interruptionAbortsAControllerWaitAndRestoresInterruptStatus() {
        final ManualTime time = new ManualTime(0);
        final SocketVisibilityController controller =
                new SocketVisibilityController(0, Long.MAX_VALUE, 1, 8, 100, 1_000_000, time, (condition, nanos) -> {
                    throw new InterruptedException("test interruption");
                });

        try {
            final IOException failure = assertThrows(IOException.class, () -> controller.awaitReadable(1, 0, false));

            assertInstanceOf(InterruptedIOException.class, failure);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(controller.stats().state().startsWith("ABORTED"));
        } finally {
            Thread.interrupted();
        }
    }

    private static SocketVisibilityController controller(
            final long latencyNanos,
            final long bandwidthBytesPerSecond,
            final long releaseQuantumNanos,
            final int maxRangeBytes,
            final ManualTime time) {
        return new SocketVisibilityController(
                latencyNanos, bandwidthBytesPerSecond, releaseQuantumNanos, maxRangeBytes, 100, 1_000_000, time, time);
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

        private void set(final long nanos) {
            now = nanos;
        }
    }
}
