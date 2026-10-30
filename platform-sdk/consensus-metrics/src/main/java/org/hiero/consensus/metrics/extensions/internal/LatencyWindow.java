// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The latencies recorded between two snapshots of a {@link ConcurrentLatencyRecorder}, with their percentiles and their
 * maximum. A new window is empty and reports 0.
 *
 * <p>This class is not thread-safe.
 */
public final class LatencyWindow {

    /** The bucket layout of the counts. */
    private final HistogramLayout layout;

    /** The number of values in each bucket, indexed by the bucket index. */
    private final long[] counts;

    /** The number of values. */
    private long total;

    /** The upper bound of the highest non-empty bucket, 0 if the window is empty. */
    private long max;

    /**
     * Creates an empty window.
     *
     * @param layout the bucket layout of the snapshots
     */
    LatencyWindow(@NonNull final HistogramLayout layout) {
        this.layout = layout;
        this.counts = new long[layout.bucketCount()];
    }

    /**
     * Sets the window to the latencies recorded between two snapshots, replacing its previous content.
     *
     * @param older the older snapshot
     * @param newer the newer snapshot, with no count lower than in the older one
     */
    void set(@NonNull final LatencySnapshot older, @NonNull final LatencySnapshot newer) {
        total = 0;
        int highest = 0;
        for (int i = 0, n = counts.length; i < n; i++) {
            counts[i] = newer.count(i) - older.count(i);
            if (counts[i] != 0) {
                total += counts[i];
                highest = i;
            }
        }
        max = total == 0 ? 0 : layout.upperBound(highest);
    }

    /**
     * Returns the value at a percentile, the upper bound of the bucket holding the value of the nearest rank.
     *
     * @param percentile the percentile, above 0 and below 100
     * @return the value at the percentile in nanoseconds, 0 if the window is empty
     */
    public long valueAtPercentile(final double percentile) {
        return total == 0 ? 0 : layout.valueAtPercentile(counts, total, max, percentile);
    }

    /**
     * Returns the maximum, the upper bound of the highest non-empty bucket.
     *
     * @return the maximum in nanoseconds, 0 if the window is empty
     */
    public long max() {
        return max;
    }
}
