// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A lightweight, lock-free metric accumulator that tracks count, sum, min, and max via
 * compare-and-swap loops. Use this for high-frequency call sites where allocating a queue per
 * sample would be too expensive.
 *
 * <p>Trade-off: {@code stdDev} is always {@link java.math.BigDecimal#ZERO}. Use
 * {@link StatisticsProbe} when accurate standard deviation is required.
 *
 * <p>Once {@link #aggregate()} has been called the probe is sealed; further {@link #add} calls
 * throw {@link IllegalStateException}.
 */
public class BasicProbe extends Probe {

    private final AtomicReference<DataHolder> dataRef =
            new AtomicReference<>(new DataHolder(BigInteger.ZERO, BigInteger.ZERO, Long.MAX_VALUE, Long.MIN_VALUE));
    private final AtomicReference<Statistics> statsRef = new AtomicReference<>();

    public BasicProbe(@NonNull final String name, @NonNull final ObsUnit unit) {
        super(name, unit);
    }

    @Override
    public @Nullable Statistics statistics() {
        return statsRef.get();
    }

    /** Seals the probe and computes the final statistics. Idempotent after the first call. */
    @Override
    public @NonNull Statistics aggregate() {
        Statistics stats = statsRef.get();
        if (stats != null) {
            return stats;
        }

        final DataHolder data = dataRef.get();
        if (BigInteger.ZERO.equals(data.numSamples)) {
            statsRef.set(FixedStatistics.NIL);
            return FixedStatistics.NIL;
        }

        final BigDecimal avg = new BigDecimal(data.sum).divide(new BigDecimal(data.numSamples), MATH_CONTEXT_10);

        stats = new FixedStatistics(
                unit(),
                data.numSamples,
                data.sum,
                BigInteger.valueOf(data.min),
                BigInteger.valueOf(data.max),
                avg,
                BigDecimal.ZERO);
        statsRef.set(stats);
        return stats;
    }

    /**
     * Thread-safe via a CAS loop so multiple threads may call this concurrently without locking.
     */
    @Override
    protected void doAdd(final long value) {
        while (true) {
            final DataHolder old = dataRef.get();
            final DataHolder updated = new DataHolder(
                    old.sum.add(BigInteger.valueOf(value)), // sum
                    old.numSamples.add(BigInteger.ONE), // numSamples
                    Math.min(old.min, value), // min
                    Math.max(old.max, value)); // max

            if (dataRef.compareAndSet(old, updated)) {
                return;
            }
        }
    }

    @Override
    protected boolean isAggregated() {
        return statsRef.get() != null;
    }

    private record DataHolder(BigInteger sum, BigInteger numSamples, long min, long max) {}
}
