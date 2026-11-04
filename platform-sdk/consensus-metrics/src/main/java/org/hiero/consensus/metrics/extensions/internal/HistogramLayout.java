// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.extensions.internal;

/**
 * The log-linear bucket layout of the latency gauges, and the percentile of a set of bucket counts.
 *
 * <p>Each power of two is split into {@code 2^p} buckets of equal width, where {@code p} is the number of precision
 * bits, so a bucket is at most {@code 2^-p} of its values wide and every value below {@code 2^(p+1)} has a bucket of
 * its own. The layout tracks values up to {@code 2^E - 1}, where {@code E} is the number of range bits; the top bucket
 * holds every larger value.
 *
 * <p>This is a copy of the layout of {@code LatencyHistogram} in {@code consensus-benchmark-tools}, which the
 * benchmarks use. The two never exchange data, so they only have to be correct, not identical, but a fix to one
 * likely applies to the other.
 */
public final class HistogramLayout {

    /** The number of parts per million in a percent, used to compute the rank of a percentile. */
    private static final long PPM_PER_PERCENT = 10_000;

    /** The number of parts per million in a whole, used to compute the rank of a percentile. */
    private static final long PPM_PER_WHOLE = 100 * PPM_PER_PERCENT;

    /** The number of precision bits {@code p}, between 1 and 16. */
    private final int precisionBits;

    /** A mask to extract the sub-bucket index from a value. */
    private final long subBucketMask;

    /** The maximum value that can be tracked, {@code 2^E - 1}. */
    private final long maxTrackable;

    /** The number of buckets. */
    private final int bucketCount;

    /**
     * Creates a layout.
     *
     * @param precisionBits the number of precision bits {@code p}, between 1 and 16
     * @param rangeBits     the number of range bits {@code E}, above {@code precisionBits} and at most 62
     * @throws IllegalArgumentException if a parameter is out of its range
     */
    public HistogramLayout(final int precisionBits, final int rangeBits) {
        if (precisionBits < 1 || precisionBits > 16) {
            throw new IllegalArgumentException("precisionBits must be between 1 and 16, but is " + precisionBits);
        }
        if (rangeBits <= precisionBits || rangeBits > 62) {
            throw new IllegalArgumentException("rangeBits must be above precisionBits (" + precisionBits
                    + ") and at most 62, but is " + rangeBits);
        }
        this.precisionBits = precisionBits;
        this.subBucketMask = (1L << precisionBits) - 1;
        this.maxTrackable = (1L << rangeBits) - 1;
        this.bucketCount = (rangeBits - precisionBits + 1) << precisionBits;
    }

    /**
     * Returns the maximum value that can be tracked. Larger values belong in the top bucket.
     *
     * @return the maximum trackable value, {@code 2^E - 1}
     */
    long maxTrackable() {
        return maxTrackable;
    }

    /**
     * Returns the number of buckets.
     *
     * @return the number of buckets
     */
    int bucketCount() {
        return bucketCount;
    }

    /**
     * Returns the index of the bucket that holds a value.
     *
     * @param value the value, between 0 and {@code 2^E - 1}
     * @return the bucket index
     */
    int indexOf(final long value) {
        // group 0 holds the values below 2^p, and group b > 0 the values in [2^(p+b-1), 2^(p+b))
        final int group = (64 - precisionBits) - Long.numberOfLeadingZeros(value | subBucketMask);
        return (group << precisionBits) + (int) ((value >>> Math.max(group - 1, 0)) & subBucketMask);
    }

    /**
     * Returns the lowest value of a bucket.
     *
     * @param index the bucket index
     * @return the lowest value of the bucket, inclusive
     */
    long lowerBound(final int index) {
        final int group = index >>> precisionBits;
        final long subBucket = index & subBucketMask;
        return group == 0 ? subBucket : ((1L << precisionBits) | subBucket) << (group - 1);
    }

    /**
     * Returns the highest value of a bucket.
     *
     * @param index the bucket index
     * @return the highest value of the bucket, inclusive
     */
    long upperBound(final int index) {
        final int group = index >>> precisionBits;
        return group == 0 ? lowerBound(index) : lowerBound(index) + (1L << (group - 1)) - 1;
    }

    /**
     * Returns the value at a percentile of a set of bucket counts, using the nearest rank. The value is the upper bound
     * of the bucket holding the value of that rank, clamped to the maximum. A rank in the top bucket reports the
     * maximum.
     *
     * @param counts     the number of values in each bucket
     * @param total      the sum of {@code counts}, above 0
     * @param max        the maximum value, at most the upper bound of the highest non-empty bucket
     * @param percentile the percentile, above 0 and below 100, rounded to four decimal places
     * @return the value at the percentile
     * @throws IllegalArgumentException if the percentile is not above 0 and below 100 after rounding
     */
    long valueAtPercentile(final long[] counts, final long total, final long max, final double percentile) {
        final long rank = Math.ceilDiv(Math.multiplyExact(total, ppmOf(percentile)), PPM_PER_WHOLE);
        long cumulative = 0;
        for (int i = 0, n = counts.length - 1; i < n; i++) {
            cumulative += counts[i];
            if (cumulative >= rank) {
                return Math.min(upperBound(i), max);
            }
        }
        return max;
    }

    /**
     * Converts a percentile to parts per million. The rank is computed in integers: in doubles, 99.9 / 100 * 1,000 is
     * 999.0000000000001, so rank 1,000.
     *
     * @param percentile the percentile, above 0 and below 100, rounded to four decimal places
     * @return the percentile in parts per million
     * @throws IllegalArgumentException if the percentile is not above 0 and below 100 after rounding
     */
    public static long ppmOf(final double percentile) {
        final long ppm = Math.round(percentile * PPM_PER_PERCENT);
        if (!(percentile > 0 && percentile < 100) || ppm <= 0 || ppm >= PPM_PER_WHOLE) {
            throw new IllegalArgumentException(
                    "A percentile must be above 0 and below 100 after rounding to four decimal places, but is "
                            + percentile);
        }
        return ppm;
    }
}
