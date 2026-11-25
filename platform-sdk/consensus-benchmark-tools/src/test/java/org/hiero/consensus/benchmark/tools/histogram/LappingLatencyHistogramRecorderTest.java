// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks that work outside {@link LappingLatencyHistogramRecorder#startLaps()} and
 * {@link LappingLatencyHistogramRecorder#stopLaps()} is left out of the latencies and the allocation, and that they and
 * {@link LappingLatencyHistogramRecorder#finalizeMeasurement()} refuse the wrong state.
 */
class LappingLatencyHistogramRecorderTest {

    /** Longer than any measured call in these tests, by far. */
    private static final Duration OTHER_WORK = Duration.ofMillis(50);

    /** Much more than the measured calls allocate, small enough not to trigger a garbage collection. */
    private static final int OTHER_ALLOCATION = 1 << 20;

    /** Keeps the allocation in the other work from being optimized away. */
    @SuppressWarnings("unused")
    private static byte[] sink;

    @Test
    void timeOutsideLapsIsNotRecorded() throws InterruptedException {
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startLaps();
        recorder.lap();
        recorder.stopLaps();
        Thread.sleep(OTHER_WORK);
        recorder.startLaps();
        recorder.lap();
        recorder.stopLaps();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.histogram().totalCount()).isEqualTo(2);
        assertThat(recorder.histogram().max()).isLessThan(OTHER_WORK.toNanos());
    }

    @Test
    void timeWithinLapsIsRecorded() throws InterruptedException {
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startLaps();
        recorder.lap();
        Thread.sleep(OTHER_WORK);
        recorder.lap();
        recorder.stopLaps();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.histogram().max()).isGreaterThanOrEqualTo(OTHER_WORK.toNanos());
    }

    @Test
    void allocationOutsideLapsIsNotCounted() {
        System.gc();
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startLaps();
        recorder.lap();
        recorder.stopLaps();
        sink = new byte[OTHER_ALLOCATION];
        recorder.startLaps();
        recorder.lap();
        recorder.stopLaps();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isLessThan(OTHER_ALLOCATION);
    }

    @Test
    void allocationWithinLapsIsCounted() {
        System.gc();
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startLaps();
        recorder.lap();
        sink = new byte[OTHER_ALLOCATION];
        recorder.lap();
        recorder.stopLaps();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isGreaterThanOrEqualTo(OTHER_ALLOCATION);
    }

    @Test
    void startLapsWhileStartedThrows() {
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startLaps();

        assertThatThrownBy(recorder::startLaps).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stopLapsWithoutStartThrows() {
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();

        assertThatThrownBy(recorder::stopLaps).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalizeMeasurementWhileLapsStartedThrows() {
        final LappingLatencyHistogramRecorder recorder = new LappingLatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startLaps();

        assertThatThrownBy(recorder::finalizeMeasurement).isInstanceOf(IllegalStateException.class);
    }
}
