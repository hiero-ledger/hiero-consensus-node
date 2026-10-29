// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Checks recording into {@link ConcurrentLatencyRecorder} and taking snapshots, also while several threads record.
 */
class ConcurrentLatencyRecorderTest {

    private static final int THREADS = 4;
    private static final int VALUES_PER_THREAD = 250_000;

    private final HistogramLayout layout = new HistogramLayout(7, 36);
    private final ConcurrentLatencyRecorder recorder = new ConcurrentLatencyRecorder(layout);

    @Test
    void snapshotCopiesTheCountsAndReturnsTheTotal() {
        recorder.record(5);
        recorder.record(5);
        recorder.record(1_000_000);

        final long[] snapshot = new long[layout.bucketCount()];
        assertThat(recorder.snapshotInto(snapshot)).isEqualTo(3);
        assertThat(snapshot[layout.indexOf(5)]).isEqualTo(2);
        assertThat(snapshot[layout.indexOf(1_000_000)]).isEqualTo(1);
        assertThat(sum(snapshot)).isEqualTo(3);
    }

    @Test
    void negativeLatenciesCountAsZero() {
        recorder.record(-1);
        recorder.record(Long.MIN_VALUE);

        final long[] snapshot = new long[layout.bucketCount()];
        assertThat(recorder.snapshotInto(snapshot)).isEqualTo(2);
        assertThat(snapshot[0]).isEqualTo(2);
    }

    @Test
    void latenciesAboveTheRangeCountInTheTopBucket() {
        recorder.record(layout.maxTrackable());
        recorder.record(layout.maxTrackable() + 1);
        recorder.record(Long.MAX_VALUE);

        final long[] snapshot = new long[layout.bucketCount()];
        assertThat(recorder.snapshotInto(snapshot)).isEqualTo(3);
        assertThat(snapshot[layout.bucketCount() - 1]).isEqualTo(3);
    }

    @Test
    void concurrentRecordingLosesNoValuesAndCountsNeverDecrease() throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            writers.add(Thread.ofPlatform().start(() -> {
                awaitQuietly(start);
                for (int i = 0; i < VALUES_PER_THREAD; i++) {
                    recorder.record(valueOf(thread, i));
                }
            }));
        }

        final AtomicBoolean writersDone = new AtomicBoolean();
        final List<String> violations = new ArrayList<>();
        final Thread reader = Thread.ofPlatform().start(() -> {
            long[] previous = new long[layout.bucketCount()];
            long[] current = new long[layout.bucketCount()];
            long previousTotal = 0;
            while (!writersDone.get()) {
                final long total = recorder.snapshotInto(current);
                if (total < previousTotal) {
                    violations.add("total " + total + " after " + previousTotal);
                }
                for (int i = 0; i < current.length; i++) {
                    if (current[i] < previous[i]) {
                        violations.add("bucket " + i + ": " + current[i] + " after " + previous[i]);
                    }
                }
                previousTotal = total;
                final long[] swap = previous;
                previous = current;
                current = swap;
            }
        });

        start.countDown();
        for (final Thread writer : writers) {
            writer.join();
        }
        writersDone.set(true);
        reader.join();
        assertThat(violations)
                .as("a snapshot never has a lower count than an earlier one")
                .isEmpty();

        final long[] expected = new long[layout.bucketCount()];
        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < VALUES_PER_THREAD; i++) {
                expected[layout.indexOf(valueOf(t, i))]++;
            }
        }
        final long[] snapshot = new long[layout.bucketCount()];
        assertThat(recorder.snapshotInto(snapshot)).isEqualTo((long) THREADS * VALUES_PER_THREAD);
        assertThat(snapshot).as("no value is lost").isEqualTo(expected);
    }

    /**
     * Returns the value that a thread records in an iteration: few distinct values, so that the threads contend on the
     * same buckets.
     */
    private static long valueOf(final int thread, final int iteration) {
        return 1_000L * (1 + (iteration + thread) % 8);
    }

    private static long sum(final long[] counts) {
        long sum = 0;
        for (final long count : counts) {
            sum += count;
        }
        return sum;
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
