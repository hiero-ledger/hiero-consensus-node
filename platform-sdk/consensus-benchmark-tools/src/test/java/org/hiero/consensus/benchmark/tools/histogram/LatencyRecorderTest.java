// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link LatencyRecorder#pause()} and {@link LatencyRecorder#resume()} leave the work between them out of
 * the latencies and the allocation.
 */
class LatencyRecorderTest {

    /** Longer than any measured call in these tests, by far. */
    private static final Duration PAUSED_WORK = Duration.ofMillis(50);

    /** Much more than the measured calls allocate, small enough not to trigger a garbage collection. */
    private static final int PAUSED_ALLOCATION = 1 << 20;

    /** Keeps the allocation in the paused work from being optimized away. */
    @SuppressWarnings("unused")
    private static byte[] sink;

    @Test
    void pausedTimeIsNotRecorded() throws InterruptedException {
        final LatencyRecorder recorder = new LatencyRecorder();
        recorder.startMeasurement();
        recorder.lap();
        recorder.pause();
        Thread.sleep(PAUSED_WORK);
        recorder.resume();
        recorder.lap();
        recorder.stopMeasurement();

        assertThat(recorder.discardedPasses()).isZero();
        assertThat(recorder.histogram().totalCount()).isEqualTo(2);
        assertThat(recorder.histogram().max()).isLessThan(PAUSED_WORK.toNanos());
    }

    @Test
    void timeWithoutPauseIsRecorded() throws InterruptedException {
        final LatencyRecorder recorder = new LatencyRecorder();
        recorder.startMeasurement();
        recorder.lap();
        Thread.sleep(PAUSED_WORK);
        recorder.lap();
        recorder.stopMeasurement();

        assertThat(recorder.discardedPasses()).isZero();
        assertThat(recorder.histogram().max()).isGreaterThanOrEqualTo(PAUSED_WORK.toNanos());
    }

    @Test
    void pausedAllocationIsNotCounted() {
        System.gc();
        final LatencyRecorder recorder = new LatencyRecorder();
        recorder.startMeasurement();
        recorder.lap();
        recorder.pause();
        sink = new byte[PAUSED_ALLOCATION];
        recorder.resume();
        recorder.lap();
        recorder.stopMeasurement();

        assertThat(recorder.discardedPasses()).isZero();
        assertThat(recorder.allocatedBytes()).isLessThan(PAUSED_ALLOCATION);
    }

    @Test
    void allocationWithoutPauseIsCounted() {
        System.gc();
        final LatencyRecorder recorder = new LatencyRecorder();
        recorder.startMeasurement();
        recorder.lap();
        sink = new byte[PAUSED_ALLOCATION];
        recorder.lap();
        recorder.stopMeasurement();

        assertThat(recorder.discardedPasses()).isZero();
        assertThat(recorder.allocatedBytes()).isGreaterThanOrEqualTo(PAUSED_ALLOCATION);
    }
}
