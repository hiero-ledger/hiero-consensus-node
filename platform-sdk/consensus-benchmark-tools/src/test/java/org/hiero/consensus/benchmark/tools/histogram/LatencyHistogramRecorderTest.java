// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link LatencyHistogramRecorder} records one latency per measurement, that work between measurements is
 * left out of the latencies and the allocation, and that {@link LatencyHistogramRecorder#startMeasurement()},
 * {@link LatencyHistogramRecorder#stopMeasurement()}, and {@link LatencyHistogramRecorder#finalizeMeasurement()} refuse
 * the wrong state.
 */
class LatencyHistogramRecorderTest {

    /** Longer than any measured call in these tests, by far. */
    private static final Duration OTHER_WORK = Duration.ofMillis(50);

    /** Much more than the measured calls allocate, small enough not to trigger a garbage collection. */
    private static final int OTHER_ALLOCATION = 1 << 20;

    /** Keeps the allocation in the other work from being optimized away. */
    @SuppressWarnings("unused")
    private static byte[] sink;

    @Test
    void timeBetweenMeasurementsIsNotRecorded() throws InterruptedException {
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startMeasurement();
        recorder.stopMeasurement();
        Thread.sleep(OTHER_WORK);
        recorder.startMeasurement();
        recorder.stopMeasurement();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.histogram().totalCount()).isEqualTo(2);
        assertThat(recorder.histogram().max()).isLessThan(OTHER_WORK.toNanos());
    }

    @Test
    void timeWithinAMeasurementIsRecorded() throws InterruptedException {
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startMeasurement();
        Thread.sleep(OTHER_WORK);
        recorder.stopMeasurement();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.histogram().totalCount()).isEqualTo(1);
        assertThat(recorder.histogram().max()).isGreaterThanOrEqualTo(OTHER_WORK.toNanos());
    }

    @Test
    void allocationBetweenMeasurementsIsNotCounted() {
        System.gc();
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startMeasurement();
        recorder.stopMeasurement();
        sink = new byte[OTHER_ALLOCATION];
        recorder.startMeasurement();
        recorder.stopMeasurement();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isLessThan(OTHER_ALLOCATION);
    }

    @Test
    void allocationWithinAMeasurementIsCounted() {
        System.gc();
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startMeasurement();
        sink = new byte[OTHER_ALLOCATION];
        recorder.stopMeasurement();
        recorder.finalizeMeasurement();

        assertThat(recorder.discardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isGreaterThanOrEqualTo(OTHER_ALLOCATION);
    }

    @Test
    void startMeasurementWhileRunningThrows() {
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startMeasurement();

        assertThatThrownBy(recorder::startMeasurement).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stopMeasurementWithoutStartThrows() {
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();

        assertThatThrownBy(recorder::stopMeasurement).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finalizeMeasurementWhileMeasuringThrows() {
        final LatencyHistogramRecorder recorder = new LatencyHistogramRecorder();
        recorder.prepareToMeasure();
        recorder.startMeasurement();

        assertThatThrownBy(recorder::finalizeMeasurement).isInstanceOf(IllegalStateException.class);
    }
}
