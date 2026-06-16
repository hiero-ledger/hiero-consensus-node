// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

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
 * <p>{@link #add(Statistics)} only accumulates the component; the aggregate is computed lazily on
 * the first read after an add. All methods are {@code synchronized}.
 */
public class CompositeStatistics implements Statistics {

    private final ObsUnit unit;
    private final List<Statistics> componentStatistics = new ArrayList<>();
    private Statistics composite = null;

    /**
     * @param unit the unit of the combined statistics
     */
    public CompositeStatistics(@NonNull final ObsUnit unit) {
        this.unit = requireNonNull(unit);
    }

    /**
     * Adds a component to the aggregate. The combined result is recomputed lazily on the next read.
     *
     * @param stats the component statistics to include in the aggregate
     */
    public synchronized void add(@NonNull final Statistics stats) {
        requireNonNull(stats);
        componentStatistics.add(stats);
        composite = null; // invalidate; recomputed lazily on the next read
    }

    private synchronized Statistics composite() {
        if (composite == null) {
            composite = calculateCompositeStatistics();
        }
        return composite;
    }

    private Statistics calculateCompositeStatistics() {
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
            return FixedStatistics.nil(unit);
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

        return new FixedStatistics(unit, numSamples, sum, min, max, avg, stdDev);
    }

    @Override
    public ObsUnit unit() {
        return unit;
    }

    @Override
    public synchronized BigInteger numSamples() {
        return composite().numSamples();
    }

    @Override
    public synchronized BigInteger sum() {
        return composite().sum();
    }

    @Override
    public synchronized BigInteger min() {
        return composite().min();
    }

    @Override
    public synchronized BigInteger max() {
        return composite().max();
    }

    @Override
    public synchronized BigDecimal avg() {
        return composite().avg();
    }

    @Override
    public synchronized BigDecimal stdDev() {
        return composite().stdDev();
    }
}
