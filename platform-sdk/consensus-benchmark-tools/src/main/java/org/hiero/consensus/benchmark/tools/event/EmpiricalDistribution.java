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
 * }</pre>
 */
public final class EmpiricalDistribution {

    /** The values of the table. */
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
     * @param values      the values that can be drawn
     * @param frequencies how often each value occurs, as counts or as shares; {@code frequencies[i]} belongs to
     *                    {@code values[i]}
     * @return the distribution
     * @throws IllegalArgumentException if the table is empty, the arrays differ in length, or a frequency is not positive
     *                                  and finite
     */
    @NonNull
    public static EmpiricalDistribution of(@NonNull final long[] values, @NonNull final double[] frequencies) {
        if (values.length == 0 || values.length != frequencies.length) {
            throw new IllegalArgumentException("Expected as many frequencies as values, and at least one, but got "
                    + values.length + " values and " + frequencies.length + " frequencies");
        }
        double sum = 0;
        for (final double frequency : frequencies) {
            // Positive frequencies keep the cumulative probabilities strictly increasing, which sample() relies on.
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
     * Draws a value. Every draw takes one {@link Random#nextDouble()}, also for a constant distribution.
     *
     * @param random the source of randomness
     * @return the drawn value
     */
    public long sample(@NonNull final Random random) {
        // The drawn value is the first one whose cumulative probability exceeds u.
        final double u = random.nextDouble();
        final int index = Arrays.binarySearch(cumulative, u);
        return values[index >= 0 ? index + 1 : -index - 1];
    }
}
