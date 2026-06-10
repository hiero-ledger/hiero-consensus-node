// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Statistics} that combines multiple component {@link Statistics} objects into a single
 * population-level aggregate.
 *
 * <p>The combined standard deviation is computed via the parallel-groups formula:
 * <pre>
 *   combinedStdDev = sqrt( sum_i( n_i * (stdDev_i² + (avg_i - combinedAvg)²) ) / N )
 * </pre>
 * where {@code N} is the total sample count across all components.
 *
 * <p>{@link #add(Statistics)} is {@code synchronized}; the aggregate is recomputed eagerly on
 * every call.
 */
public class CompositeStatistics implements Statistics {

    private final ObsUnit unit;
    private final AtomicReference<Statistics> compositeStatsRef = new AtomicReference<>(FixedStatistics.NIL);
    private final List<Statistics> componentStatistics = new LinkedList<>();

    public CompositeStatistics(@NonNull final ObsUnit unit) {
        this.unit = requireNonNull(unit);
    }

    public synchronized void add(@NonNull final Statistics stats) {
        requireNonNull(stats);
        componentStatistics.add(stats);
        calculateCompositeStatistics();
    }

    private void calculateCompositeStatistics() {
        BigInteger numSamples = BigInteger.ZERO;
        BigInteger sum = BigInteger.ZERO;
        BigInteger min = null;
        BigInteger max = null;

        for (final Statistics stats : componentStatistics) {
            if (min == null || min.compareTo(stats.min()) > 0) {
                min = stats.min();
            }
            if (max == null || max.compareTo(stats.max()) < 0) {
                max = stats.max();
            }

            numSamples = numSamples.add(stats.numSamples());
            sum = sum.add(stats.sum());
        }

        if (BigInteger.ZERO.equals(numSamples)) {
            compositeStatsRef.set(FixedStatistics.NIL);
            return;
        }

        final BigDecimal avg = new BigDecimal(sum).divide(new BigDecimal(numSamples), MATH_CONTEXT_10);
        BigDecimal stdDev = BigDecimal.ZERO;

        // Calculate the combined standard deviation
        // Note: this is a population-based standard deviation, NOT a sample-based one
        for (final Statistics stats : componentStatistics) {
            final BigDecimal bd1 = new BigDecimal(stats.numSamples())
                    .multiply(stats.stdDev().pow(2, MATH_CONTEXT_10), MATH_CONTEXT_10);
            final BigDecimal bd2 = new BigDecimal(stats.numSamples())
                    .multiply(stats.avg().subtract(avg, MATH_CONTEXT_10).pow(2, MATH_CONTEXT_10), MATH_CONTEXT_10);

            stdDev = stdDev.add(bd1, MATH_CONTEXT_10).add(bd2, MATH_CONTEXT_10);
        }

        stdDev = stdDev.divide(new BigDecimal(numSamples), MATH_CONTEXT_10).sqrt(MATH_CONTEXT_10);

        compositeStatsRef.set(new FixedStatistics(unit, numSamples, sum, min, max, avg, stdDev));
    }

    @Override
    public ObsUnit unit() {
        return unit;
    }

    @Override
    public BigInteger numSamples() {
        return compositeStatsRef.get().numSamples();
    }

    @Override
    public BigInteger sum() {
        return compositeStatsRef.get().sum();
    }

    @Override
    public BigInteger min() {
        return compositeStatsRef.get().min();
    }

    @Override
    public BigInteger max() {
        return compositeStatsRef.get().max();
    }

    @Override
    public BigDecimal avg() {
        return compositeStatsRef.get().avg();
    }

    @Override
    public BigDecimal stdDev() {
        return compositeStatsRef.get().stdDev();
    }
}
