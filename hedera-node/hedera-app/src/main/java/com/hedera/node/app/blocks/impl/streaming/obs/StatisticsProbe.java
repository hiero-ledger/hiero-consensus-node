// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;
import static java.util.Objects.requireNonNull;

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
public class StatisticsProbe {

    private final String name;
    private final ObsUnit unit;
    private volatile boolean isClosed = false;
    private Statistics statistics = null;
    private Queue<Long> values = new ConcurrentLinkedQueue<>();

    public StatisticsProbe(@NonNull final String name, @NonNull final ObsUnit unit) {
        this.name = requireNonNull(name);
        this.unit = requireNonNull(unit);
    }

    public @NonNull String name() {
        return name;
    }

    public @NonNull ObsUnit unit() {
        return unit;
    }

    /**
     * Records {@code value}. Thread-safe via {@link ConcurrentLinkedQueue}.
     *
     * @throws IllegalStateException if {@link #aggregate()} has already been called
     */
    public void add(final long value) {
        if (isClosed) {
            throw new IllegalStateException("Probe is already aggregated; cannot add more values");
        }

        values.add(value);
    }

    /** Returns the aggregated statistics, or {@code null} if {@link #aggregate()} has not yet been called. */
    public @Nullable Statistics statistics() {
        return statistics;
    }

    /**
     * Seals the probe, computes statistics (including stdDev), and releases the internal value
     * queue to allow GC. Idempotent after the first call.
     *
     * @return {@link FixedStatistics#NIL} if no samples were recorded
     */
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
            statistics = FixedStatistics.NIL;
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
        statistics = new FixedStatistics(unit, numSamples, sum, min, max, avg, stdDev);

        values = null; // clear values to reclaim memory

        return statistics;
    }

    @Override
    public String toString() {
        String s = name + " ";

        if (statistics == null) {
            s += "{ <In Progress> }";
        } else {
            s += Statistics.toString(statistics);
        }
        return s;
    }
}
