// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A copy of the cumulative counts of a {@link ConcurrentLatencyRecorder}, with their total. A new snapshot is empty. A
 * snapshot can be reused: copying overwrites its previous content.
 *
 * <p>This class is not thread-safe.
 */
final class LatencySnapshot {

    /** The number of values in each bucket, indexed by the bucket index. */
    private final long[] counts;

    /** The sum of the counts. */
    private long total;

    /**
     * Creates an empty snapshot.
     *
     * @param layout the bucket layout of the recorder
     */
    LatencySnapshot(@NonNull final HistogramLayout layout) {
        this.counts = new long[layout.bucketCount()];
    }

    /**
     * Overwrites this snapshot with the current counts of a recorder.
     *
     * @param recorder the recorder to copy, with the same bucket layout
     */
    void copyFrom(@NonNull final ConcurrentLatencyRecorder recorder) {
        total = recorder.snapshotInto(counts);
    }

    /**
     * Returns the count of a bucket.
     *
     * @param index the bucket index
     * @return the number of values in the bucket
     */
    long count(final int index) {
        return counts[index];
    }

    /**
     * Returns the sum of the counts.
     *
     * @return the number of values
     */
    long total() {
        return total;
    }
}
