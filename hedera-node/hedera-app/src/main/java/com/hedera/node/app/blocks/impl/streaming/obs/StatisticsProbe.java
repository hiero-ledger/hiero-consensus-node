// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A metric accumulator that collects every individual sample and computes full statistics —
 * including population standard deviation — when {@link #aggregate()} is called.
 *
 * <p>More memory-intensive than {@link BasicProbe} because it stores every raw value until
 * aggregation. After {@link #aggregate()} is called the internal queue is nullified to reclaim
 * memory.
 *
 * <p>Once {@link #aggregate()} has been called the probe is sealed; further {@link #add} calls
 * throw {@link IllegalStateException}.
 */
public class StatisticsProbe extends Probe {

    private volatile boolean isClosed = false;
    private Statistics statistics = null;
    private Queue<Long> values = new ConcurrentLinkedQueue<>();

    public StatisticsProbe(@NonNull final String name, @NonNull final ObsUnit unit) {
        super(name, unit);
    }

    @Override
    public @Nullable Statistics statistics() {
        return statistics;
    }

    /**
     * Seals the probe, computes statistics (including stdDev), and releases the internal value
     * queue to allow GC. Idempotent after the first call.
     *
     * @return {@link FixedStatistics#nil(ObsUnit)} if no samples were recorded
     */
    @Override
    public @NonNull Statistics aggregate() {
        if (statistics != null) {
            return statistics;
        }

        isClosed = true;

        BigInteger min = null;
        BigInteger max = null;
        BigInteger sum = BigInteger.ZERO;
        BigInteger numSamples = BigInteger.ZERO;

        for (final long value : values) {
            final BigInteger val = BigInteger.valueOf(value);

            if (min == null || min.compareTo(val) > 0) {
                min = val;
            }
            if (max == null || max.compareTo(val) < 0) {
                max = val;
            }

            numSamples = numSamples.add(BigInteger.ONE);
            sum = sum.add(val);
        }

        if (BigInteger.ZERO.equals(numSamples)) {
            statistics = FixedStatistics.nil(unit());
            return statistics;
        }

        final BigDecimal avg = new BigDecimal(sum).divide(new BigDecimal(numSamples), MATH_CONTEXT_10);
        BigDecimal stdDev = BigDecimal.ZERO;

        for (final long value : values) {
            final BigDecimal bd1 = BigDecimal.valueOf(value).subtract(avg);
            stdDev = stdDev.add(bd1.pow(2));
        }

        stdDev = stdDev.divide(new BigDecimal(numSamples), 10, RoundingMode.HALF_EVEN)
                .sqrt(MATH_CONTEXT_10);
        statistics = new FixedStatistics(unit(), numSamples, sum, min, max, avg, stdDev);

        values = null; // clear values to reclaim memory

        return statistics;
    }

    /** Thread-safe via {@link ConcurrentLinkedQueue}. */
    @Override
    protected void doAdd(final long value) {
        values.add(value);
    }

    @Override
    protected boolean isAggregated() {
        return isClosed;
    }
}
