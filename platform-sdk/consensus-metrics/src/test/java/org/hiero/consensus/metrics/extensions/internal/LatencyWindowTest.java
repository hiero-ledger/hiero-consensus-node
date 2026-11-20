// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Checks that {@link LatencyWindow} reports on the difference of two snapshots.
 */
class LatencyWindowTest {

    /** Values below 2^(p+1) = 256 have a bucket of their own, so they are reported exactly. */
    private final HistogramLayout layout = new HistogramLayout(7, 36);

    private final ConcurrentLatencyRecorder recorder = new ConcurrentLatencyRecorder(layout);
    private final LatencyWindow window = new LatencyWindow(layout);

    @Test
    void aNewWindowReportsZero() {
        assertThat(window.valueAtPercentile(50)).isZero();
        assertThat(window.max()).isZero();
    }

    @Test
    void reportsOnlyTheValuesBetweenTheSnapshots() {
        record(100, 200);
        final LatencySnapshot older = snapshot();
        record(98, 10);
        record(2, 100);
        window.set(older, snapshot());

        assertThat(window.valueAtPercentile(50)).isEqualTo(10);
        assertThat(window.valueAtPercentile(98)).isEqualTo(10);
        assertThat(window.valueAtPercentile(99)).isEqualTo(100);
        assertThat(window.max()).isEqualTo(100);
    }

    @Test
    void setReplacesThePreviousContent() {
        final LatencySnapshot start = snapshot();
        record(1, 200);
        window.set(start, snapshot());
        final LatencySnapshot middle = snapshot();
        record(1, 10);
        window.set(middle, snapshot());

        assertThat(window.valueAtPercentile(50)).isEqualTo(10);
        assertThat(window.max()).isEqualTo(10);
    }

    @Test
    void anEmptyDifferenceReportsZero() {
        final LatencySnapshot start = snapshot();
        record(1, 200);
        final LatencySnapshot end = snapshot();
        window.set(start, end);
        window.set(end, end);

        assertThat(window.valueAtPercentile(50)).isZero();
        assertThat(window.max()).isZero();
    }

    @Test
    void maxIsTheUpperBoundOfTheHighestBucket() {
        final LatencySnapshot start = snapshot();
        record(1, 1_000_003);
        window.set(start, snapshot());

        final long upperBound = layout.upperBound(layout.indexOf(1_000_003));
        assertThat(upperBound).isGreaterThan(1_000_003);
        assertThat(window.valueAtPercentile(50)).isEqualTo(upperBound);
        assertThat(window.max()).isEqualTo(upperBound);
    }

    @Test
    void aValueAboveTheRangeReportsTheHighestTrackedValue() {
        final LatencySnapshot start = snapshot();
        record(1, Long.MAX_VALUE);
        window.set(start, snapshot());

        assertThat(window.valueAtPercentile(50)).isEqualTo(layout.maxTrackable());
        assertThat(window.max()).isEqualTo(layout.maxTrackable());
    }

    private void record(final int count, final long nanos) {
        for (int i = 0; i < count; i++) {
            recorder.record(nanos);
        }
    }

    private LatencySnapshot snapshot() {
        final LatencySnapshot snapshot = new LatencySnapshot(layout);
        snapshot.copyFrom(recorder);
        return snapshot;
    }
}
