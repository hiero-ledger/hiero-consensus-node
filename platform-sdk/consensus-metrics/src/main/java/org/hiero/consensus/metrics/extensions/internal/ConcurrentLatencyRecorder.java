// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts latencies in log-linear buckets, from any number of threads, without locking.
 *
 * <p>Recording is one uncontended atomic increment of the value's bucket. The counts are cumulative and never reset,
 * so the difference of two snapshots is the number of values recorded in between. A {@link SlidingLatencyWindow} takes
 * the snapshots.
 */
public final class ConcurrentLatencyRecorder {

    /** The bucket layout. */
    private final HistogramLayout layout;

    /** The number of values in each bucket, indexed by the bucket index. */
    private final AtomicLongArray counts;

    /**
     * Creates a recorder without values.
     *
     * @param layout the bucket layout
     */
    public ConcurrentLatencyRecorder(@NonNull final HistogramLayout layout) {
        this.layout = layout;
        this.counts = new AtomicLongArray(layout.bucketCount());
    }

    /**
     * Records one latency. A negative latency, which a clock adjustment can produce, counts as 0. A latency above the
     * tracked range counts in the top bucket.
     *
     * @param nanos the latency in nanoseconds
     */
    public void record(final long nanos) {
        counts.incrementAndGet(layout.indexOf(Math.clamp(nanos, 0, layout.maxTrackable())));
    }

    /**
     * Returns the bucket layout.
     *
     * @return the bucket layout
     */
    @NonNull
    HistogramLayout layout() {
        return layout;
    }

    /**
     * Copies the counts. The copy is not one instant across buckets, because values may be recorded while it is made,
     * but every count only grows, so no bucket of a later copy has a lower count.
     *
     * @param target the array to copy into, with one element per bucket
     * @return the sum of the copied counts
     */
    long snapshotInto(@NonNull final long[] target) {
        long total = 0;
        for (int i = 0, n = target.length; i < n; i++) {
            final long count = counts.getOpaque(i);
            target[i] = count;
            total += count;
        }
        return total;
    }
}
