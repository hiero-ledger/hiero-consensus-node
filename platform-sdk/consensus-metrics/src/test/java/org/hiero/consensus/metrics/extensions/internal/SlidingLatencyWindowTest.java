// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks which values {@link SlidingLatencyWindow} holds and when it advances.
 */
class SlidingLatencyWindowTest {

    private static final Duration INTERVAL = Duration.ofSeconds(3);
    /** Three intervals, so a value leaves the window at the fourth advance after it was recorded. */
    private static final Duration LENGTH = Duration.ofSeconds(9);
    /** One snapshot per interval of the window. */
    private static final int SNAPSHOTS = 3;

    /** Values below 2^(p+1) = 256 have a bucket of their own, so they are reported exactly. */
    private final ConcurrentLatencyRecorder recorder = new ConcurrentLatencyRecorder(new HistogramLayout(7, 36));

    private final FakeTime time = new FakeTime();
    private final SlidingLatencyWindow slidingWindow =
            new SlidingLatencyWindow(recorder, time, INTERVAL, LENGTH, SNAPSHOTS);

    @Test
    void isEmptyBeforeTheFirstValue() {
        assertValues(0, 0, 0);
        time.tick(INTERVAL);
        assertValues(0, 0, 0);
    }

    @Test
    void firstWindowHoldsEverythingSinceTheStart() {
        record(100, 10);
        time.tick(INTERVAL);
        assertValues(10, 10, 10);

        record(100, 200);
        time.tick(INTERVAL);
        assertValues(10, 200, 200);
    }

    @Test
    void valuesLeaveTheWindowAfterItsLength() {
        record(10, 200);
        time.tick(INTERVAL);
        assertValues(200, 200, 200);

        record(10, 10);
        for (int round = 2; round <= 3; round++) {
            time.tick(INTERVAL);
            assertThat(max()).as("round " + round).isEqualTo(200);
        }
        time.tick(INTERVAL);
        assertValues(10, 10, 10);
    }

    @Test
    void lengthIsRoundedUpToWholeIntervals() {
        final SlidingLatencyWindow sevenSeconds =
                new SlidingLatencyWindow(recorder, time, INTERVAL, Duration.ofSeconds(7), SNAPSHOTS);
        record(10, 200);
        time.tick(INTERVAL);
        assertThat(sevenSeconds.current().max()).isEqualTo(200);

        record(10, 10);
        for (int round = 2; round <= 3; round++) {
            time.tick(INTERVAL);
            assertThat(sevenSeconds.current().max()).as("round " + round).isEqualTo(200);
        }
        time.tick(INTERVAL);
        assertThat(sevenSeconds.current().max())
                .as("7 s round up to three intervals")
                .isEqualTo(10);
    }

    @Test
    void fewerSnapshotsThanIntervalsAdvanceInLongerSteps() {
        // 18 s in at most 3 snapshots: a step of 6 s
        final SlidingLatencyWindow stepped =
                new SlidingLatencyWindow(recorder, time, INTERVAL, Duration.ofSeconds(18), 3);
        record(10, 200);
        time.tick(INTERVAL);
        assertThat(stepped.current().max())
                .as("3 s, the first step has not ended")
                .isZero();
        time.tick(INTERVAL);
        assertThat(stepped.current().max()).as("6 s").isEqualTo(200);

        record(20, 10);
        time.tick(INTERVAL);
        assertThat(stepped.current().valueAtPercentile(50))
                .as("9 s, within the step")
                .isEqualTo(200);
        time.tick(INTERVAL);
        assertThat(stepped.current().valueAtPercentile(50))
                .as("12 s, the next step")
                .isEqualTo(10);

        for (int seconds = 15; seconds <= 21; seconds += 3) {
            time.tick(INTERVAL);
            assertThat(stepped.current().max()).as(seconds + " s").isEqualTo(200);
        }
        time.tick(INTERVAL);
        assertThat(stepped.current().max())
                .as("24 s, the fourth snapshot drops the 200s")
                .isEqualTo(10);
    }

    @Test
    void stepsAreWholeIntervalsAndTheLengthWholeSteps() {
        // 9 s in at most 2 snapshots: a step of 6 s, so the window covers 12 s
        final SlidingLatencyWindow stepped = new SlidingLatencyWindow(recorder, time, INTERVAL, LENGTH, 2);
        record(10, 200);
        time.tick(Duration.ofSeconds(6));
        assertThat(stepped.current().max()).as("6 s").isEqualTo(200);

        record(10, 10);
        time.tick(Duration.ofSeconds(6));
        assertThat(stepped.current().max()).as("12 s").isEqualTo(200);
        time.tick(Duration.ofSeconds(6));
        assertThat(stepped.current().max())
                .as("18 s, the third snapshot drops the 200s")
                .isEqualTo(10);
    }

    @Test
    void extraReadsWithinARoundDoNotAdvance() {
        record(10, 200);
        time.tick(INTERVAL);
        assertThat(max()).isEqualTo(200);

        record(10, 10);
        for (int round = 2; round <= 3; round++) {
            time.tick(INTERVAL);
            for (int read = 0; read < 10; read++) {
                assertThat(max()).as("round " + round).isEqualTo(200);
                time.tick(Duration.ofMillis(100));
            }
        }
        time.tick(INTERVAL);
        assertThat(max())
                .as("the 200s leave at the fourth advance, not earlier")
                .isEqualTo(10);
    }

    @Test
    void roundsUpToHalfAnIntervalEarlyOrLateAdvanceOnce() {
        record(10, 200);
        time.tick(Duration.ofMillis(4_400));
        assertThat(max()).isEqualTo(200);

        record(10, 10);
        time.tick(Duration.ofMillis(1_400));
        assertThat(max()).as("less than half an interval, no advance").isEqualTo(200);
        time.tick(Duration.ofMillis(200));
        assertThat(max()).as("1.6 s after the last advance").isEqualTo(200);
        time.tick(Duration.ofMillis(4_400));
        assertThat(max()).as("4.4 s after the last advance").isEqualTo(200);
        time.tick(Duration.ofMillis(1_600));
        assertThat(max()).as("the fourth advance drops the 200s").isEqualTo(10);
    }

    @Test
    void windowWithoutValuesKeepsTheLastValues() {
        record(98, 10);
        record(2, 200);
        time.tick(INTERVAL);
        assertValues(10, 200, 200);

        for (int round = 2; round <= 8; round++) {
            time.tick(INTERVAL);
            assertValues(10, 200, 200);
        }
    }

    @Test
    void invalidArgumentsThrow() {
        assertThatThrownBy(() -> new SlidingLatencyWindow(recorder, time, INTERVAL, Duration.ofSeconds(2), SNAPSHOTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingLatencyWindow(recorder, time, Duration.ZERO, LENGTH, SNAPSHOTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingLatencyWindow(recorder, time, INTERVAL, LENGTH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void record(final int count, final long nanos) {
        for (int i = 0; i < count; i++) {
            recorder.record(nanos);
        }
    }

    private long max() {
        return slidingWindow.current().max();
    }

    private void assertValues(final long p50, final long p99, final long max) {
        final LatencyWindow window = slidingWindow.current();
        assertThat(window.valueAtPercentile(50)).as("p50").isEqualTo(p50);
        assertThat(window.valueAtPercentile(99)).as("p99").isEqualTo(p99);
        assertThat(window.max()).as("max").isEqualTo(max);
    }
}
