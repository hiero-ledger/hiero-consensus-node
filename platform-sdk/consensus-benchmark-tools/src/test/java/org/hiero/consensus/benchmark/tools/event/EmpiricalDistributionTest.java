// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks that {@link EmpiricalDistribution} draws each value with the share of its frequency, the same values for the
 * same seed, and the quantiles of its table.
 */
class EmpiricalDistributionTest {

    private static final int DRAWS = 100_000;

    @Test
    void constantAlwaysGivesItsValue() {
        final EmpiricalDistribution distribution = EmpiricalDistribution.constant(445);
        final Random random = new Random(1);

        assertThat(draw(distribution, random)).containsOnly(445L);
    }

    @Test
    void drawsFollowTheFrequencies() {
        final EmpiricalDistribution distribution =
                EmpiricalDistribution.of(new long[] {1, 2, 5}, new double[] {1, 2, 7});
        final Random random = new Random(1);

        final int[] counts = new int[6];
        for (int i = 0; i < DRAWS; i++) {
            counts[(int) distribution.sample(random)]++;
        }

        assertThat(counts[0] + counts[3] + counts[4]).isZero();
        assertThat((double) counts[1] / DRAWS).isCloseTo(0.1, within(0.01));
        assertThat((double) counts[2] / DRAWS).isCloseTo(0.2, within(0.01));
        assertThat((double) counts[5] / DRAWS).isCloseTo(0.7, within(0.01));
    }

    @Test
    void sameSeedGivesSameDraws() {
        final EmpiricalDistribution distribution =
                EmpiricalDistribution.of(new long[] {0, 1, 2, 3}, new double[] {0.4, 0.3, 0.2, 0.1});
        final Random first = new Random(42);
        final Random second = new Random(42);

        assertThat(draw(distribution, first)).containsExactly(draw(distribution, second));
    }

    @Test
    void quantileIsTheSmallestValueThatTheShareStaysAtOrBelow() {
        // cumulative probabilities: 1 -> 0.1, 2 -> 0.3, 5 -> 1
        final EmpiricalDistribution distribution =
                EmpiricalDistribution.of(new long[] {1, 2, 5}, new double[] {1, 2, 7});

        assertThat(distribution.quantile(0)).isEqualTo(1);
        assertThat(distribution.quantile(0.1)).isEqualTo(1);
        assertThat(distribution.quantile(0.15)).isEqualTo(2);
        assertThat(distribution.quantile(0.3)).isEqualTo(2);
        assertThat(distribution.quantile(0.31)).isEqualTo(5);
        assertThat(distribution.quantile(1)).isEqualTo(5);
    }

    @Test
    void quantileOfCountsIsExactAtTheBoundary() {
        // 99 of 100 events have no transaction
        final EmpiricalDistribution distribution = EmpiricalDistribution.of(new long[] {0, 1}, new double[] {99, 1});

        assertThat(distribution.quantile(0.99)).isZero();
        assertThat(distribution.quantile(0.995)).isEqualTo(1);
    }

    @Test
    void quantileOfAConstantIsItsValue() {
        final EmpiricalDistribution distribution = EmpiricalDistribution.constant(445);

        assertThat(distribution.quantile(0)).isEqualTo(445);
        assertThat(distribution.quantile(0.5)).isEqualTo(445);
        assertThat(distribution.quantile(1)).isEqualTo(445);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, Double.NaN})
    void sharesOutsideZeroToOneAreRejected(final double q) {
        final EmpiricalDistribution distribution = EmpiricalDistribution.constant(445);

        assertThatThrownBy(() -> distribution.quantile(q)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Draws 1,000 values. */
    private static long[] draw(final EmpiricalDistribution distribution, final Random random) {
        return LongStream.generate(() -> distribution.sample(random))
                .limit(1_000)
                .toArray();
    }

    static Stream<Arguments> invalidTables() {
        return Stream.of(
                Arguments.of(new long[] {}, new double[] {}),
                Arguments.of(new long[] {1, 2}, new double[] {1}),
                Arguments.of(new long[] {2, 1}, new double[] {1, 1}),
                Arguments.of(new long[] {1, 1}, new double[] {1, 1}),
                Arguments.of(new long[] {1, 2}, new double[] {1, 0}),
                Arguments.of(new long[] {1, 2}, new double[] {1, -1}),
                Arguments.of(new long[] {1, 2}, new double[] {1, Double.NaN}),
                Arguments.of(new long[] {1, 2}, new double[] {1, Double.POSITIVE_INFINITY}));
    }

    @ParameterizedTest
    @MethodSource("invalidTables")
    void invalidTablesAreRejected(final long[] values, final double[] frequencies) {
        assertThatThrownBy(() -> EmpiricalDistribution.of(values, frequencies))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
