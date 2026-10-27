// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks the bucket layout of {@link HistogramLayout}, a copy of the checks of {@code LatencyHistogram} in
 * {@code consensus-benchmark-tools}: every value lies in its bucket, the buckets are contiguous and as narrow as the
 * precision promises, and percentiles match the exact values.
 */
class HistogramLayoutTest {

    private static final long SEED = 0;
    private static final int RANDOM_VALUES = 1_000_000;
    private static final long EXHAUSTIVE_LIMIT = 1L << 20;

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void everyValueLiesInItsBucket(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        for (final long value : testValues(rangeBits)) {
            final int index = layout.indexOf(value);
            assertThat(value)
                    .as(() -> "value in bucket " + index)
                    .isBetween(layout.lowerBound(index), layout.upperBound(index));
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void indexNeverDecreases(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        final long[] values = testValues(rangeBits);
        for (int i = 1; i < values.length; i++) {
            final long previous = values[i - 1];
            final long value = values[i];
            assertThat(layout.indexOf(value))
                    .as(() -> "index of " + value + ", compared to the index of " + previous)
                    .isGreaterThanOrEqualTo(layout.indexOf(previous));
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void bucketsAreContiguous(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        assertThat(layout.lowerBound(0)).as("the first bucket starts at 0").isZero();
        for (int i = 0; i < layout.bucketCount(); i++) {
            final int index = i;
            assertThat(layout.indexOf(layout.lowerBound(index)))
                    .as(() -> "index of the lower bound of bucket " + index)
                    .isEqualTo(index);
            assertThat(layout.indexOf(layout.upperBound(index)))
                    .as(() -> "index of the upper bound of bucket " + index)
                    .isEqualTo(index);
            if (index + 1 < layout.bucketCount()) {
                assertThat(layout.lowerBound(index + 1))
                        .as(() -> "no gap or overlap after bucket " + index)
                        .isEqualTo(layout.upperBound(index) + 1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void bucketWidthIsBoundedByThePrecision(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        for (int i = 1 << precisionBits; i < layout.bucketCount(); i++) {
            final int index = i;
            final long lower = layout.lowerBound(index);
            final long width = layout.upperBound(index) - lower + 1;
            assertThat(width << precisionBits)
                    .as(() -> "width " + width + " of bucket " + index + ", times 2^p, compared to its lower bound")
                    .isLessThanOrEqualTo(lower);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void valuesBelowTwoToThePrecisionPlusOneAreExact(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        for (int i = 0; i < 2 << precisionBits; i++) {
            assertThat(layout.indexOf(i)).isEqualTo(i);
            assertThat(layout.lowerBound(i)).isEqualTo(i);
            assertThat(layout.upperBound(i)).isEqualTo(i);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void highestTrackedValueIsInTheTopBucket(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        final long maxTrackable = (1L << rangeBits) - 1;
        assertThat(layout.maxTrackable()).isEqualTo(maxTrackable);
        assertThat(layout.bucketCount()).isEqualTo((rangeBits - precisionBits + 1) << precisionBits);
        assertThat(layout.indexOf(maxTrackable)).isEqualTo(layout.bucketCount() - 1);
        assertThat(layout.upperBound(layout.bucketCount() - 1)).isEqualTo(maxTrackable);
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void indexMatchesTheReference(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        for (int i = 0; i < layout.bucketCount(); i++) {
            assertIndexMatchesTheReference(layout, precisionBits, layout.lowerBound(i));
            assertIndexMatchesTheReference(layout, precisionBits, layout.upperBound(i));
        }
        for (final long value : powersOfTwoAndNeighbors(rangeBits)) {
            assertIndexMatchesTheReference(layout, precisionBits, value);
        }
        for (final long value : logUniformValues(rangeBits)) {
            assertIndexMatchesTheReference(layout, precisionBits, value);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void percentilesMatchTheExactValues(final int precisionBits, final int rangeBits) {
        final HistogramLayout layout = new HistogramLayout(precisionBits, rangeBits);
        final long[] sorted = logUniformValues(rangeBits);
        Arrays.sort(sorted);
        final long[] counts = new long[layout.bucketCount()];
        for (final long value : sorted) {
            counts[layout.indexOf(value)]++;
        }
        final long max = layout.upperBound(layout.indexOf(sorted[sorted.length - 1]));
        final int topBucket = layout.bucketCount() - 1;

        final Random random = new Random(SEED);
        for (int i = 0; i < 1_000; i++) {
            final long ppm = 1 + random.nextLong(999_999);
            final long reported = layout.valueAtPercentile(counts, sorted.length, max, ppm / 10_000.0);
            final long exact = sorted[(int) Math.ceilDiv(sorted.length * ppm, 1_000_000L) - 1];
            final int index = layout.indexOf(exact);
            final long expected = index == topBucket ? max : Math.min(layout.upperBound(index), max);
            assertThat(reported)
                    .as(() -> "ppm " + ppm + ", exact value " + exact)
                    .isEqualTo(expected);
            assertThat(reported)
                    .as(() -> "ppm " + ppm + ", at most 2^-p above the exact value " + exact)
                    .isBetween(exact, exact + (exact >>> precisionBits));
        }
    }

    @Test
    void rankOnAnExactIntegerIsNotRoundedUp() {
        final HistogramLayout layout = new HistogramLayout(10, 36);
        final long[] counts = new long[layout.bucketCount()];
        for (int value = 1; value <= 1_000; value++) {
            counts[layout.indexOf(value)]++;
        }
        assertThat(layout.valueAtPercentile(counts, 1_000, 1_000, 50)).isEqualTo(500);
        assertThat(layout.valueAtPercentile(counts, 1_000, 1_000, 99)).isEqualTo(990);
        assertThat(layout.valueAtPercentile(counts, 1_000, 1_000, 99.9)).isEqualTo(999);
    }

    @Test
    void aRankInTheTopBucketReportsTheMax() {
        final HistogramLayout layout = new HistogramLayout(7, 36);
        final long[] counts = new long[layout.bucketCount()];
        counts[layout.indexOf(1)] = 98;
        counts[layout.bucketCount() - 1] = 2;
        final long max = layout.maxTrackable();
        assertThat(layout.valueAtPercentile(counts, 100, max, 99.5)).isEqualTo(max);
        assertThat(layout.valueAtPercentile(counts, 100, max, 98)).isEqualTo(1);
    }

    @Test
    void invalidPercentilesThrow() {
        final HistogramLayout layout = new HistogramLayout(7, 36);
        final long[] counts = new long[layout.bucketCount()];
        counts[0] = 1;
        for (final double percentile : new double[] {
            0, 100, -1, 101, 0.00004, 99.99996, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY
        }) {
            assertThatThrownBy(() -> layout.valueAtPercentile(counts, 1, 0, percentile))
                    .as(() -> "percentile " + percentile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatNoException().isThrownBy(() -> layout.valueAtPercentile(counts, 1, 0, 0.0001));
        assertThatNoException().isThrownBy(() -> layout.valueAtPercentile(counts, 1, 0, 99.9999));
    }

    @Test
    void invalidLayoutsThrow() {
        assertThatThrownBy(() -> new HistogramLayout(0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistogramLayout(17, 40)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistogramLayout(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistogramLayout(10, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HistogramLayout(10, 63)).isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> new HistogramLayout(1, 2));
        assertThatNoException().isThrownBy(() -> new HistogramLayout(16, 17));
        assertThatNoException().isThrownBy(() -> new HistogramLayout(1, 62));
    }

    private static void assertIndexMatchesTheReference(
            final HistogramLayout layout, final int precisionBits, final long value) {
        assertThat(layout.indexOf(value))
                .as(() -> "index of " + value)
                .isEqualTo(referenceIndexOf(precisionBits, value));
    }

    /**
     * Computes the bucket index independently of the bit tricks: the group from the bit length, and the sub-bucket by
     * dividing by the group's bucket width.
     */
    private static int referenceIndexOf(final int precisionBits, final long value) {
        final BigInteger big = BigInteger.valueOf(value);
        final int group = Math.max(0, big.bitLength() - precisionBits);
        if (group == 0) {
            return (int) value;
        }
        final BigInteger width = BigInteger.TWO.pow(group - 1);
        final BigInteger subBucket = big.divide(width).subtract(BigInteger.TWO.pow(precisionBits));
        return group * (1 << precisionBits) + subBucket.intValueExact();
    }

    /**
     * Returns the sorted, distinct values to check: every value up to 2^20, every power of two with its neighbors, and
     * seeded log-uniform values, all within the tracked range.
     */
    private static long[] testValues(final int rangeBits) {
        final long maxTrackable = (1L << rangeBits) - 1;
        return LongStream.concat(
                        LongStream.concat(
                                LongStream.rangeClosed(0, Math.min(EXHAUSTIVE_LIMIT, maxTrackable)),
                                LongStream.of(powersOfTwoAndNeighbors(rangeBits))),
                        LongStream.of(logUniformValues(rangeBits)))
                .sorted()
                .distinct()
                .toArray();
    }

    private static long[] powersOfTwoAndNeighbors(final int rangeBits) {
        final long maxTrackable = (1L << rangeBits) - 1;
        return LongStream.rangeClosed(0, rangeBits)
                .flatMap(bit -> LongStream.of((1L << bit) - 1, 1L << bit, (1L << bit) + 1))
                .filter(value -> value <= maxTrackable)
                .toArray();
    }

    private static long[] logUniformValues(final int rangeBits) {
        final long maxTrackable = (1L << rangeBits) - 1;
        final double logRange = Math.log(maxTrackable + 1.0);
        final Random random = new Random(SEED);
        return LongStream.generate(() -> Math.min((long) Math.exp(random.nextDouble() * logRange), maxTrackable))
                .limit(RANDOM_VALUES)
                .toArray();
    }
}
