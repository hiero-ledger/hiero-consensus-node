// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.simple;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;

/**
 * Tracks the average time taken for an operation by accumulating the time and the number of operation. The actual
 * average is calculated when written to the output, thus providing the most accurate average for the write period with
 * minimal overhead. This class accumulates time in microseconds and stores it in an integer, which means the maximum
 * accumulated time is about 35 minutes before it overflows.
 */
public class AccumulatedAverageTime {
    private static final String UNIT_APPENDIX = " (ms)";
    private final IntegerPairAccumulator<Double> accumulator;

    public AccumulatedAverageTime(
            final Metrics metrics, final String category, final String name, final String description) {

        accumulator = metrics.getOrCreate(new IntegerPairAccumulator.Config<>(
                        category, name + UNIT_APPENDIX, Double.class, AccumulatedAverageTime::averageMillis)
                .withDescription(description)
                .withFormat(FloatFormats.FORMAT_10_6));
    }

    /**
     * Add time to the accumulated value
     *
     * @param nanoTime
     * 		the time in nanoseconds
     */
    public void add(final long nanoTime) {
        accumulator.update((int) (nanoTime * UnitConstants.NANOSECONDS_TO_MICROSECONDS), 1);
    }

    public double get() {
        return accumulator.get();
    }

    private static double averageMillis(final int sum, final int count) {
        if (count == 0) {
            // avoid division by 0
            return 0;
        }
        return ((double) sum) / count * UnitConstants.MICROSECONDS_TO_MILLISECONDS;
    }
}
