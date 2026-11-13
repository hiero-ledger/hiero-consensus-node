// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import edu.umd.cs.findbugs.annotations.NonNull;

/** The layout of the histograms that the recorders fill and {@link LatencyProfiler} merges, which all must share. */
final class HistogramLayout {

    /** Buckets at most 2^-10 (about 0.1%) of their values wide, and exact below 2,048 ns. */
    private static final int PRECISION_BITS = 10;

    /** Tracks latencies up to 2^36 - 1 ns, about 68.7 s. */
    private static final int RANGE_BITS = 36;

    private HistogramLayout() {}

    /**
     * Creates an empty histogram with this layout.
     *
     * @return an empty histogram
     */
    @NonNull
    static LatencyHistogram newHistogram() {
        return new LatencyHistogram(PRECISION_BITS, RANGE_BITS);
    }
}
