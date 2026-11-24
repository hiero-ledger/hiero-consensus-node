// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Random;

/**
 * Seeded draws from a frequency table: each value is drawn with a probability proportional to its frequency.
 *
 * <pre>{@code
 * // 0 with probability 0.7, 1 with probability 0.2, 5 with probability 0.1
 * EmpiricalDistribution transactionsPerEvent = EmpiricalDistribution.of(new long[] {0, 1, 5}, new double[] {7, 2, 1});
 * long transactions = transactionsPerEvent.sample(random);
 * long p90 = transactionsPerEvent.quantile(0.9); // 1
 * }</pre>
 */
public final class EmpiricalDistribution {

    /** The values of the table, in ascending order. */
    private final long[] values;

    /** {@code cumulative[i]} is the probability of drawing one of {@code values[0..i]}. The last entry is 1. */
    private final double[] cumulative;

    private EmpiricalDistribution(@NonNull final long[] values, @NonNull final double[] cumulative) {
        this.values = values;
        this.cumulative = cumulative;
    }

    /**
     * Creates a distribution from a frequency table.
     *
     * @param values      the values that can be drawn, in ascending order
     * @param frequencies how often each value occurs, as counts or as shares; {@code frequencies[i]} belongs to
     *                    {@code values[i]}. Counts give exact quantiles, because their sums are exact.
     * @return the distribution
     * @throws IllegalArgumentException if the table is empty, the arrays differ in length, the values are not in
     *                                  ascending order, or a frequency is not positive and finite
     */
    @NonNull
    public static EmpiricalDistribution of(@NonNull final long[] values, @NonNull final double[] frequencies) {
        if (values.length == 0 || values.length != frequencies.length) {
            throw new IllegalArgumentException("Expected as many frequencies as values, and at least one, but got "
                    + values.length + " values and " + frequencies.length + " frequencies");
        }
        for (int i = 1; i < values.length; i++) {
            if (values[i] <= values[i - 1]) {
                throw new IllegalArgumentException(
                        "Values must be in ascending order, but " + values[i] + " follows " + values[i - 1]);
            }
        }
        double sum = 0;
        for (final double frequency : frequencies) {
            // Positive frequencies keep the cumulative probabilities strictly increasing, which quantile() relies on.
            if (!Double.isFinite(frequency) || frequency <= 0) {
                throw new IllegalArgumentException("Frequencies must be positive and finite, but got " + frequency);
            }
            sum += frequency;
        }

        final double[] cumulative = new double[frequencies.length];
        double running = 0;
        for (int i = 0; i < frequencies.length; i++) {
            running += frequencies[i];
            cumulative[i] = running / sum;
        }
        // Rounding must not leave a gap below 1, where a draw would find no value.
        cumulative[cumulative.length - 1] = 1.0;
        return new EmpiricalDistribution(values.clone(), cumulative);
    }

    /**
     * Creates a distribution that always gives the same value, for a fixed shape.
     *
     * @param value the value
     * @return the distribution
     */
    @NonNull
    public static EmpiricalDistribution constant(final long value) {
        return of(new long[] {value}, new double[] {1});
    }

    /**
     * Draws a value: the quantile of a uniform random number. Every draw takes one {@link Random#nextDouble()}, also
     * for a constant distribution.
     *
     * @param random the source of randomness
     * @return the drawn value
     */
    public long sample(@NonNull final Random random) {
        return quantile(random.nextDouble());
    }

    /**
     * Returns the smallest value that a share {@code q} of the draws stays at or below. A fixed shape takes its values
     * from a production table this way: {@code constant(table.quantile(0.99))} gives every event the value that 99% of
     * the events have at most.
     *
     * @param q the share, from 0 to 1
     * @return the value
     * @throws IllegalArgumentException if {@code q} is not between 0 and 1
     */
    public long quantile(final double q) {
        if (!(q >= 0 && q <= 1)) {
            throw new IllegalArgumentException("Expected a share between 0 and 1, but got " + q);
        }
        // The first value whose cumulative probability reaches q.
        final int index = Arrays.binarySearch(cumulative, q);
        return values[index >= 0 ? index : -index - 1];
    }

    /**
     * Returns the median: the smallest value that half of the draws stay at or below, {@code quantile(0.5)}.
     *
     * @return the median
     */
    public long median() {
        return quantile(0.5);
    }
}
