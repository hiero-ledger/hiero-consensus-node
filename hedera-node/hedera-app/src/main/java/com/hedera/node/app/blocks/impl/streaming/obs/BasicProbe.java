// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * A lightweight, lock-free, allocation-free metric accumulator that tracks count, sum, min, and
 * max. Use this for high-frequency call sites.
 *
 * <p>The accumulators are updated independently (there is no cross-field atomicity), so
 * {@link #aggregate()} must only be called once concurrent {@link #add} calls have ceased; the
 * gather task's grace period guarantees this in production.
 *
 * <p>Trade-off: {@code stdDev} is always {@link java.math.BigDecimal#ZERO}. Use
 * {@link StatisticsProbe} when accurate standard deviation is required.
 *
 * <p>Once {@link #aggregate()} has been called the probe is sealed; further {@link #add} calls
 * throw {@link IllegalStateException}.
 */
public class BasicProbe extends Probe {

    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);
    private final AtomicReference<Statistics> statsRef = new AtomicReference<>();

    public BasicProbe(@NonNull final String name, @NonNull final ObsUnit unit) {
        super(name, unit);
    }

    @Override
    public @Nullable Statistics statistics() {
        return statsRef.get();
    }

    /**
     * Seals the probe and computes the final statistics. Idempotent after the first call.
     *
     * @return the aggregated statistics, or {@link FixedStatistics#nil(ObsUnit)} if no samples were recorded
     */
    @Override
    public @NonNull Statistics aggregate() {
        final Statistics existing = statsRef.get();
        if (existing != null) {
            return existing;
        }

        final long numSamples = count.sum();
        final Statistics stats;
        if (numSamples == 0) {
            stats = FixedStatistics.nil(unit());
        } else {
            final long total = sum.sum();
            final BigDecimal avg = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(numSamples), MATH_CONTEXT_10);
            stats = new FixedStatistics(
                    unit(),
                    BigInteger.valueOf(numSamples),
                    BigInteger.valueOf(total),
                    BigInteger.valueOf(min.get()),
                    BigInteger.valueOf(max.get()),
                    avg,
                    BigDecimal.ZERO);
        }

        statsRef.compareAndSet(null, stats);
        return statsRef.get();
    }

    /**
     * Lock-free and allocation-free; each accumulator is updated independently.
     *
     * @param value the value to record
     */
    @Override
    protected void doAdd(final long value) {
        count.increment();
        sum.add(value);
        min.accumulateAndGet(value, Math::min);
        max.accumulateAndGet(value, Math::max);
    }

    @Override
    protected boolean isAggregated() {
        return statsRef.get() != null;
    }
}
