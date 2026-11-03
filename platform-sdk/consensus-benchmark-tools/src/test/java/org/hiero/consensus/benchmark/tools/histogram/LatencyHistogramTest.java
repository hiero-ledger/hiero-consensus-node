// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the percentiles of {@link LatencyHistogram} against exact values, its edge cases, and merging.
 */
class LatencyHistogramTest {

    private static final long SEED = 0;
    private static final int PRECISION_BITS = 10;
    private static final int RANGE_BITS = 36;
    private static final long MAX_TRACKABLE = (1L << RANGE_BITS) - 1;
    private static final double[] STANDARD_PERCENTILES = {0, 50, 90, 99, 99.9, 99.99, 100};

    enum Distribution {
        UNIFORM,
        LOG_UNIFORM,
        /** 99% around 10 ns and 1% around 10 ms */
        BIMODAL,
        /** Pareto with a minimum of 1 µs and a shape of 1.5 */
        PARETO;

        long next(final Random random, final long maxTrackable) {
            return switch (this) {
                case UNIFORM -> random.nextLong(maxTrackable + 1);
                case LOG_UNIFORM -> logUniform(random, maxTrackable);
                case BIMODAL ->
                    random.nextInt(100) == 0 ? 10_000_000 + random.nextLong(1_000_000) : 10 + random.nextLong(10);
                case PARETO -> (long) (1_000 / Math.pow(1 - random.nextDouble(), 1 / 1.5));
            };
        }
    }

    static Stream<Arguments> layoutsAndDistributions() {
        return Stream.of(new int[] {1, 8}, new int[] {7, 36}, new int[] {10, 36}, new int[] {10, 62})
                .flatMap(layout -> Arrays.stream(Distribution.values())
                        .map(distribution -> Arguments.of(layout[0], layout[1], distribution)));
    }

    @ParameterizedTest
    @MethodSource("layoutsAndDistributions")
    void percentilesMatchTheExactValues(final int precisionBits, final int rangeBits, final Distribution distribution) {
        final long maxTrackable = (1L << rangeBits) - 1;
        final Random random = new Random(SEED);
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        final long[] sorted = new long[1_000_000];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = distribution.next(random, maxTrackable);
            histogram.record(sorted[i]);
        }
        Arrays.sort(sorted);

        final List<Long> ppms = new ArrayList<>();
        for (final double percentile : STANDARD_PERCENTILES) {
            ppms.add(Math.round(percentile * 10_000));
        }
        for (int i = 0; i < 1_000; i++) {
            ppms.add(1 + random.nextLong(999_999));
        }

        final long max = sorted[sorted.length - 1];
        final int topBucket = histogram.bucketCount() - 1;
        for (final long ppm : ppms) {
            final long reported = histogram.valueAtPercentile(ppm / 10_000.0);
            if (ppm == 0) {
                assertThat(reported).as("percentile 0 is the exact minimum").isEqualTo(sorted[0]);
                continue;
            }
            if (ppm == 1_000_000) {
                assertThat(reported).as("percentile 100 is the exact maximum").isEqualTo(max);
                continue;
            }
            final long exact = sorted[(int) Math.ceilDiv(sorted.length * ppm, 1_000_000L) - 1];
            final int index = histogram.indexOf(Math.min(exact, maxTrackable));
            final long expected = index == topBucket ? max : Math.min(histogram.upperBound(index), max);
            assertThat(reported)
                    .as(() -> "ppm " + ppm + ", exact value " + exact)
                    .isEqualTo(expected);
            if (index < topBucket || max <= maxTrackable) {
                assertThat(reported)
                        .as(() -> "ppm " + ppm + ", at most 2^-p above the exact value " + exact)
                        .isBetween(exact, exact + (exact >>> precisionBits));
            }
        }
    }

    @Test
    void emptyHistogramThrows() {
        final LatencyHistogram histogram = newHistogram();
        assertThat(histogram.totalCount()).isZero();
        assertThatThrownBy(histogram::min).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(histogram::max).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(histogram::mean).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> histogram.valueAtPercentile(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> histogram.valueAtPercentile(50)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> histogram.valueAtPercentile(100)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void singleValue() {
        final LatencyHistogram histogram = histogramOf(123_457);
        assertThat(histogram.totalCount()).isEqualTo(1);
        assertThat(histogram.min()).isEqualTo(123_457);
        assertThat(histogram.max()).isEqualTo(123_457);
        assertThat(histogram.mean()).isEqualTo(123_457);
        for (final double percentile : STANDARD_PERCENTILES) {
            assertThat(histogram.valueAtPercentile(percentile))
                    .as("the only value, clamped to the max")
                    .isEqualTo(123_457);
        }
    }

    @Test
    void twoValues() {
        final LatencyHistogram histogram = histogramOf(100, 5_000_003);
        assertThat(histogram.valueAtPercentile(0)).isEqualTo(100);
        assertThat(histogram.valueAtPercentile(50)).as("rank 1 of 2").isEqualTo(100);
        assertThat(histogram.valueAtPercentile(50.0001))
                .as("rank 2 of 2, clamped to the max")
                .isEqualTo(5_000_003);
        assertThat(histogram.valueAtPercentile(100)).isEqualTo(5_000_003);
    }

    @Test
    void identicalValues() {
        final long[] values = new long[1_000];
        Arrays.fill(values, 123_456_789);
        final LatencyHistogram histogram = histogramOf(values);
        assertThat(histogram.totalCount()).isEqualTo(1_000);
        assertThat(histogram.mean()).isEqualTo(123_456_789);
        for (final double percentile : STANDARD_PERCENTILES) {
            assertThat(histogram.valueAtPercentile(percentile))
                    .as("clamped to the max")
                    .isEqualTo(123_456_789);
        }
    }

    @Test
    void rankOnAnExactIntegerIsNotRoundedUp() {
        final LatencyHistogram histogram =
                histogramOf(LongStream.rangeClosed(1, 1_000).toArray());
        assertThat(histogram.valueAtPercentile(50)).isEqualTo(500);
        assertThat(histogram.valueAtPercentile(99)).isEqualTo(990);
        assertThat(histogram.valueAtPercentile(99.9)).isEqualTo(999);
    }

    @Test
    void zeroAndHundredAreTheExactMinAndMax() {
        final LatencyHistogram histogram = histogramOf(1_000_001, 3_000_000, 5_000_003);
        assertThat(histogram.valueAtPercentile(0)).isEqualTo(1_000_001);
        assertThat(histogram.valueAtPercentile(100)).isEqualTo(5_000_003);
        assertThat(histogram.min()).isEqualTo(1_000_001);
        assertThat(histogram.max()).isEqualTo(5_000_003);
    }

    @Test
    void invalidPercentilesThrow() {
        final LatencyHistogram histogram = histogramOf(1);
        for (final double percentile :
                new double[] {-0.0001, 100.0001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> histogram.valueAtPercentile(percentile))
                    .as(() -> "percentile " + percentile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void negativeValueThrows() {
        final LatencyHistogram histogram = newHistogram();
        assertThatThrownBy(() -> histogram.record(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> histogram.record(Long.MIN_VALUE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(histogram.totalCount())
                .as("a rejected value is not recorded")
                .isZero();
        assertThatThrownBy(histogram::max).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void valuesAboveTheRangeKeepTheExactMax(final int precisionBits, final int rangeBits) {
        final long maxTrackable = (1L << rangeBits) - 1;
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        final int topBucket = histogram.bucketCount() - 1;
        for (int i = 0; i < 98; i++) {
            histogram.record(1);
        }

        histogram.record(maxTrackable + 1);
        assertThat(histogram.countAt(topBucket)).isEqualTo(1);
        assertThat(histogram.max()).isEqualTo(maxTrackable + 1);
        assertThat(histogram.valueAtPercentile(99.5))
                .as("a rank in the top bucket reports the max")
                .isEqualTo(maxTrackable + 1);

        histogram.record(Long.MAX_VALUE);
        assertThat(histogram.countAt(topBucket)).isEqualTo(2);
        assertThat(histogram.max()).isEqualTo(Long.MAX_VALUE);
        assertThat(histogram.valueAtPercentile(99.5))
                .as("a rank in the top bucket reports the max")
                .isEqualTo(Long.MAX_VALUE);
        assertThat(histogram.valueAtPercentile(98))
                .as("ranks below the top bucket are unaffected")
                .isEqualTo(1);
    }

    @Test
    void meanIsExact() {
        final LatencyHistogram histogram = histogramOf(1, 2, 1_000_003, MAX_TRACKABLE + 7);
        assertThat(histogram.mean()).isEqualTo((1 + 2 + 1_000_003 + MAX_TRACKABLE + 7) / 4.0);
    }

    @Test
    void invalidLayoutsThrow() {
        assertThatThrownBy(() -> new LatencyHistogram(0, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LatencyHistogram(17, 40)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LatencyHistogram(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LatencyHistogram(10, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LatencyHistogram(10, 63)).isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> new LatencyHistogram(1, 2));
        assertThatNoException().isThrownBy(() -> new LatencyHistogram(16, 17));
        assertThatNoException().isThrownBy(() -> new LatencyHistogram(1, 62));
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void mergedPartsEqualTheWholeStream(final int precisionBits, final int rangeBits) {
        final long[] values = mergeTestValues();
        final LatencyHistogram whole = new LatencyHistogram(precisionBits, rangeBits);
        for (final long value : values) {
            whole.record(value);
        }

        final List<LatencyHistogram> parts = split(values, precisionBits, rangeBits);
        final LatencyHistogram merged = new LatencyHistogram(precisionBits, rangeBits);
        for (final LatencyHistogram part : parts) {
            merged.add(part);
        }
        assertSameContent(whole, merged);

        Collections.shuffle(parts, new Random(SEED));
        final LatencyHistogram mergedShuffled = new LatencyHistogram(precisionBits, rangeBits);
        for (final LatencyHistogram part : parts) {
            mergedShuffled.add(part);
        }
        assertSameContent(whole, mergedShuffled);
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void addingAnEmptyHistogramChangesNothing(final int precisionBits, final int rangeBits) {
        final LatencyHistogram expected = new LatencyHistogram(precisionBits, rangeBits);
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        for (final long value : mergeTestValues()) {
            expected.record(value);
            histogram.record(value);
        }
        histogram.add(new LatencyHistogram(precisionBits, rangeBits));
        assertSameContent(expected, histogram);
    }

    @ParameterizedTest
    @CsvSource({"1, 8", "7, 36", "10, 36", "10, 62"})
    void addingIntoAnEmptyHistogramCopiesTheOther(final int precisionBits, final int rangeBits) {
        final LatencyHistogram expected = new LatencyHistogram(precisionBits, rangeBits);
        final LatencyHistogram other = new LatencyHistogram(precisionBits, rangeBits);
        for (final long value : mergeTestValues()) {
            expected.record(value);
            other.record(value);
        }
        final LatencyHistogram histogram = new LatencyHistogram(precisionBits, rangeBits);
        histogram.add(other);
        assertSameContent(expected, histogram);
        assertSameContent(expected, other);
    }

    @Test
    void addingADifferentLayoutThrows() {
        final LatencyHistogram histogram = newHistogram();
        assertThatThrownBy(() -> histogram.add(new LatencyHistogram(7, RANGE_BITS)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> histogram.add(new LatencyHistogram(PRECISION_BITS, 35)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetEmptiesTheHistogramForReuse() {
        final LatencyHistogram histogram = histogramOf(mergeTestValues());
        histogram.reset();
        assertThat(histogram.totalCount()).isZero();
        assertThatThrownBy(histogram::min).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(histogram::max).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(histogram::mean).isInstanceOf(IllegalStateException.class);

        histogram.record(42);
        histogram.record(7);
        assertSameContent(histogramOf(42, 7), histogram);
    }

    private static LatencyHistogram newHistogram() {
        return new LatencyHistogram(PRECISION_BITS, RANGE_BITS);
    }

    private static LatencyHistogram histogramOf(final long... values) {
        final LatencyHistogram histogram = newHistogram();
        for (final long value : values) {
            histogram.record(value);
        }
        return histogram;
    }

    /**
     * Returns seeded log-uniform values up to 2^40, beyond the range of most test layouts, but small enough that their
     * sum fits in a {@code long}.
     */
    private static long[] mergeTestValues() {
        final Random random = new Random(SEED);
        return LongStream.generate(() -> logUniform(random, 1L << 40))
                .limit(100_000)
                .toArray();
    }

    /**
     * Records each value into one of five histograms, chosen at random.
     */
    private static List<LatencyHistogram> split(final long[] values, final int precisionBits, final int rangeBits) {
        final Random random = new Random(SEED);
        final List<LatencyHistogram> parts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            parts.add(new LatencyHistogram(precisionBits, rangeBits));
        }
        for (final long value : values) {
            parts.get(random.nextInt(parts.size())).record(value);
        }
        return parts;
    }

    private static long logUniform(final Random random, final long maxValue) {
        return Math.min((long) Math.exp(random.nextDouble() * Math.log(maxValue + 1.0)), maxValue);
    }

    private static void assertSameContent(final LatencyHistogram expected, final LatencyHistogram actual) {
        assertThat(actual.bucketCount()).isEqualTo(expected.bucketCount());
        for (int i = 0; i < expected.bucketCount(); i++) {
            final int index = i;
            assertThat(actual.countAt(index))
                    .as(() -> "count of bucket " + index)
                    .isEqualTo(expected.countAt(index));
        }
        assertThat(actual.totalCount()).isEqualTo(expected.totalCount());
        assertThat(actual.min()).isEqualTo(expected.min());
        assertThat(actual.max()).isEqualTo(expected.max());
        assertThat(actual.mean()).isEqualTo(expected.mean());
    }
}
