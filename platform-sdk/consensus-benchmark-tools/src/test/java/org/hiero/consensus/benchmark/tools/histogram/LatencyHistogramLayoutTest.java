// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks the bucket layout of {@link LatencyHistogram}: every value lies in its bucket, and the buckets are contiguous
 * and as narrow as the precision promises.
 */
class LatencyHistogramLayoutTest {

    private static final long SEED = 0;
    private static final int RANDOM_VALUES = 1_000_000;
    private static final long EXHAUSTIVE_LIMIT = 1L << 20;

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void everyValueLiesInItsBucket(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        for (final long value : testValues(rangeBits)) {
            final int index = histogram.indexOf(value);
            assertThat(value)
                    .as(() -> "value in bucket " + index)
                    .isBetween(histogram.lowerBound(index), histogram.upperBound(index));
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void indexNeverDecreases(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        final long[] values = testValues(rangeBits);
        for (int i = 1; i < values.length; i++) {
            final long previous = values[i - 1];
            final long value = values[i];
            assertThat(histogram.indexOf(value))
                    .as(() -> "index of " + value + ", compared to the index of " + previous)
                    .isGreaterThanOrEqualTo(histogram.indexOf(previous));
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void bucketsAreContiguous(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        assertThat(histogram.lowerBound(0)).as("the first bucket starts at 0").isZero();
        for (int i = 0; i < histogram.bucketCount(); i++) {
            final int index = i;
            assertThat(histogram.indexOf(histogram.lowerBound(index)))
                    .as(() -> "index of the lower bound of bucket " + index)
                    .isEqualTo(index);
            assertThat(histogram.indexOf(histogram.upperBound(index)))
                    .as(() -> "index of the upper bound of bucket " + index)
                    .isEqualTo(index);
            if (index + 1 < histogram.bucketCount()) {
                assertThat(histogram.lowerBound(index + 1))
                        .as(() -> "no gap or overlap after bucket " + index)
                        .isEqualTo(histogram.upperBound(index) + 1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void bucketWidthIsBoundedByThePrecision(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        for (int i = 1 << precisionBits; i < histogram.bucketCount(); i++) {
            final int index = i;
            final long lower = histogram.lowerBound(index);
            final long width = histogram.upperBound(index) - lower + 1;
            assertThat(width << precisionBits)
                    .as(() -> "width " + width + " of bucket " + index + ", times 2^p, compared to its lower bound")
                    .isLessThanOrEqualTo(lower);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void valuesBelowTwoToThePrecisionPlusOneAreExact(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        for (int i = 0; i < 2 << precisionBits; i++) {
            assertThat(histogram.indexOf(i)).isEqualTo(i);
            assertThat(histogram.lowerBound(i)).isEqualTo(i);
            assertThat(histogram.upperBound(i)).isEqualTo(i);
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void highestTrackedValueIsInTheTopBucket(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        final long maxTrackable = (1L << rangeBits) - 1;
        assertThat(histogram.bucketCount()).isEqualTo((rangeBits - precisionBits + 1) << precisionBits);
        assertThat(histogram.indexOf(maxTrackable)).isEqualTo(histogram.bucketCount() - 1);
        assertThat(histogram.upperBound(histogram.bucketCount() - 1)).isEqualTo(maxTrackable);
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void indexMatchesTheReference(final int precisionBits, final int rangeBits) {
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        for (int i = 0; i < histogram.bucketCount(); i++) {
            assertIndexMatchesTheReference(histogram, precisionBits, histogram.lowerBound(i));
            assertIndexMatchesTheReference(histogram, precisionBits, histogram.upperBound(i));
        }
        for (final long value : powersOfTwoAndNeighbors(rangeBits)) {
            assertIndexMatchesTheReference(histogram, precisionBits, value);
        }
        for (final long value : logUniformValues(rangeBits)) {
            assertIndexMatchesTheReference(histogram, precisionBits, value);
        }
    }

    private static void assertIndexMatchesTheReference(
            final LatencyHistogram histogram, final int precisionBits, final long value) {
        assertThat(histogram.indexOf(value))
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
