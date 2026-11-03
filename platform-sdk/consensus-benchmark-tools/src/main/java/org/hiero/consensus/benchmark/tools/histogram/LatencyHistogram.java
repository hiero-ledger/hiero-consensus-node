// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;

/**
 * Collects a large number of latency measurements in nanoseconds and summarizes them, for example "99% of the calls
 * took at most 18 µs", without storing the individual measurements.
 *
 * <h2>What it is for</h2>
 *
 * <p>Use it when a latency is measured very often, for example for every call in a benchmark, and you need to know how
 * the latencies are distributed, not just their average. The mean hides the rare slow calls, which often matter most.
 * Percentiles show them: the 99th percentile (p99) is the latency that 99% of the calls stay at or below, so only 1%
 * of the calls are slower. Computing percentiles exactly requires storing and sorting every measurement, which costs
 * 8 bytes per call, 800 MB for 100 million calls. This histogram answers the same questions in a fixed amount of
 * memory. The {@linkplain #min() minimum}, {@linkplain #max() maximum}, and {@linkplain #mean() mean} are exact, and
 * {@linkplain #valueAtPercentile(double) percentiles} are precise to about 0.1% with the settings the benchmarks use.
 *
 * <h2>How it works</h2>
 *
 * <p>A histogram divides the range of possible values into buckets and only counts how many values fell into each
 * bucket. To find p99 of 1,000,000 recorded values, it adds up the counts from the lowest bucket upwards until it
 * reaches the 990,000th value, and reports the upper end of that bucket, but never more than the maximum. The result
 * is therefore never below the exact value, and at most one bucket width above it.
 *
 * <p>The buckets get wider as the values grow, so that every bucket is narrow compared to its values: a difference of
 * 2 ns matters for a latency of 3 µs, but not for one of 3 ms. Each power of two, such as 4,096 to 8,191 ns, is split
 * into {@code 2^p} buckets of equal width, where {@code p} is the number of precision bits. A bucket is therefore at
 * most {@code 2^-p} of its values wide, and every value below {@code 2^(p+1)} has a bucket of its own. The histogram
 * tracks values up to {@code 2^E - 1}, where {@code E} is the number of range bits. A larger value is counted in the
 * top bucket, which is treated as unbounded: a percentile that falls into it reports the maximum.
 *
 * <p>For example, with 10 precision bits and 36 range bits, the settings of the consensus benchmarks:
 * <ul>
 *   <li>every latency from 0 to 2,047 ns has a bucket of its own, so these latencies are exact;</li>
 *   <li>from 8,192 to 16,383 ns, the buckets are 8 ns wide. A latency of 12,345 ns is counted in the bucket from
 *       12,344 to 12,351 ns. A percentile that falls into this bucket is reported as 12,351 ns (or as the maximum, if
 *       that is lower), at most 7 ns or 0.06% too high;</li>
 *   <li>from 1,048,576 to 2,097,151 ns (about 1 to 2 ms), the buckets are 1,024 ns wide;</li>
 *   <li>no bucket is wider than 1/1,024 of its values, about 0.1%;</li>
 *   <li>latencies up to 2^36 - 1 ns, about 68.7 s, are tracked; longer ones are counted in the top bucket;</li>
 *   <li>the histogram holds 27,648 counters in 216 KB. Each additional precision bit doubles this.</li>
 * </ul>
 *
 * <h2>Using it on one thread</h2>
 *
 * <pre>{@code
 * final LatencyHistogram histogram = new LatencyHistogram(10, 36);
 * for (int i = 0; i < calls; i++) {
 *     final long start = System.nanoTime();
 *     operation.run();
 *     histogram.record(System.nanoTime() - start);
 * }
 * final long p99 = histogram.valueAtPercentile(99);
 * }</pre>
 *
 * <p>Recording costs a few nanoseconds and never allocates, so it barely disturbs the measured code. Reading the clock
 * usually costs more.
 *
 * <h2>Using it on several threads</h2>
 *
 * <p>This class is not thread-safe. If two threads record into the same histogram, values are lost without any error.
 * A thread-safe histogram would need an atomic operation or a lock for every value, which would slow down the measured
 * code and distort the measurement. Instead:
 * <ol>
 *   <li>Give every measuring thread a histogram of its own, all with the same precision and range bits.</li>
 *   <li>When a thread has finished measuring, hand its histogram to one coordinating thread. The thread must not record
 *       into it after that.</li>
 *   <li>The coordinating thread creates an empty histogram with the same bits and adds all the threads' histograms to
 *       it with {@link #add(LatencyHistogram)}. The result is exactly what a single histogram would contain if it had
 *       recorded all values.</li>
 *   <li>It reads the percentiles, minimum, maximum, and mean from the combined histogram.</li>
 * </ol>
 *
 * <p>The handoff in step 2 must make the recorded values visible to the coordinating thread. The Java memory model
 * guarantees this only through a happens-before relationship, for example when the coordinating thread calls
 * {@link Thread#join()} on the measuring thread, or receives the histogram from
 * {@link java.util.concurrent.Future#get()} or a {@link java.util.concurrent.BlockingQueue}. Without it, the combined
 * histogram may silently miss values.
 *
 * <pre>{@code
 * // each measuring thread runs this as a Callable<LatencyHistogram>
 * final LatencyHistogram own = new LatencyHistogram(10, 36);
 * // ... own.record(latency) for every call ...
 * return own;
 *
 * // the coordinating thread
 * final LatencyHistogram combined = new LatencyHistogram(10, 36);
 * for (final Future<LatencyHistogram> future : futures) {
 *     combined.add(future.get()); // waits for the thread and makes its values visible
 * }
 * final long p99 = combined.valueAtPercentile(99);
 * }</pre>
 */
public final class LatencyHistogram {

    /** The number of parts per million in a percent, used to compute the rank of a percentile. */
    private static final long PPM_PER_PERCENT = 10_000;

    /** The number of parts per million in a whole, used to compute the rank of a percentile. */
    private static final long PPM_PER_WHOLE = 100 * PPM_PER_PERCENT;

    /** The number of precision bits {@code p}, between 1 and 16. */
    private final int precisionBits;

    /** The number of range bits {@code E}, above {@code precisionBits} and at most 62. */
    private final int rangeBits;

    /** A mask to extract the sub-bucket index from a value. */
    private final long subBucketMask;

    /** The maximum value that can be tracked, {@code 2^E - 1}. */
    private final long maxTrackable;

    /** The counts of values in each bucket, indexed by the bucket index. */
    private final long[] counts;

    /** The sum of all recorded values. */
    private long sum;

    /** The minimum recorded value. */
    private long min = Long.MAX_VALUE;

    /** The maximum recorded value. */
    private long max = Long.MIN_VALUE;

    /**
     * Creates an empty histogram.
     *
     * @param precisionBits the number of precision bits {@code p}, which bounds the width of a bucket to {@code 2^-p}
     *                      of its values, between 1 and 16
     * @param rangeBits     the number of range bits {@code E}, which makes {@code 2^E - 1} the highest tracked value,
     *                      above {@code precisionBits} and at most 62
     * @throws IllegalArgumentException if a parameter is out of its range
     */
    public LatencyHistogram(final int precisionBits, final int rangeBits) {
        if (precisionBits < 1 || precisionBits > 16) {
            throw new IllegalArgumentException("precisionBits must be between 1 and 16, but is " + precisionBits);
        }
        if (rangeBits <= precisionBits || rangeBits > 62) {
            throw new IllegalArgumentException("rangeBits must be above precisionBits (" + precisionBits
                    + ") and at most 62, but is " + rangeBits);
        }
        this.precisionBits = precisionBits;
        this.rangeBits = rangeBits;
        this.subBucketMask = (1L << precisionBits) - 1;
        this.maxTrackable = (1L << rangeBits) - 1;
        this.counts = new long[(rangeBits - precisionBits + 1) << precisionBits];
    }

    /**
     * Records one latency. A value above the tracked range is counted in the top bucket, and the minimum, maximum, and
     * mean still use its exact value.
     *
     * @param nanos the latency in nanoseconds
     * @throws IllegalArgumentException if the latency is negative
     */
    public void record(final long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("A latency must not be negative, but is " + nanos);
        }
        counts[indexOf(Math.min(nanos, maxTrackable))]++;
        sum += nanos;
        min = Math.min(min, nanos);
        max = Math.max(max, nanos);
    }

    /**
     * Adds all values of another histogram to this one. The other histogram is not changed.
     *
     * @param other the histogram to add, with the same layout
     * @throws IllegalArgumentException if the other histogram has a different layout
     */
    public void add(@NonNull final LatencyHistogram other) {
        if (other.precisionBits != precisionBits || other.rangeBits != rangeBits) {
            throw new IllegalArgumentException("Cannot add a histogram with " + other.precisionBits
                    + " precision bits and " + other.rangeBits + " range bits to one with " + precisionBits
                    + " precision bits and " + rangeBits + " range bits");
        }
        for (int i = 0; i < counts.length; i++) {
            counts[i] += other.counts[i];
        }
        sum += other.sum;
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
    }

    /**
     * Removes all values.
     */
    public void reset() {
        Arrays.fill(counts, 0);
        sum = 0;
        min = Long.MAX_VALUE;
        max = Long.MIN_VALUE;
    }

    /**
     * Returns the number of recorded values.
     *
     * @return the number of recorded values
     */
    public long totalCount() {
        long total = 0;
        for (final long count : counts) {
            total += count;
        }
        return total;
    }

    /**
     * Returns the exact lowest recorded value.
     *
     * @return the lowest value in nanoseconds
     * @throws IllegalStateException if the histogram is empty
     */
    public long min() {
        checkNotEmpty();
        return min;
    }

    /**
     * Returns the exact highest recorded value.
     *
     * @return the highest value in nanoseconds
     * @throws IllegalStateException if the histogram is empty
     */
    public long max() {
        checkNotEmpty();
        return max;
    }

    /**
     * Returns the exact mean of the recorded values. It stays exact while their sum fits in a {@code long}, which is
     * about 292 years.
     *
     * @return the mean in nanoseconds
     * @throws IllegalStateException if the histogram is empty
     */
    public double mean() {
        checkNotEmpty();
        return (double) sum / totalCount();
    }

    /**
     * Returns the value at a percentile, using the nearest rank. The value is the upper bound of the bucket holding the
     * value of that rank, clamped to the maximum. A rank in the top bucket reports the maximum. Percentile 0 reports
     * the minimum and percentile 100 the maximum.
     *
     * @param percentile the percentile, between 0 and 100, rounded to four decimal places
     * @return the value at the percentile in nanoseconds
     * @throws IllegalArgumentException if the percentile is not between 0 and 100
     * @throws IllegalStateException    if the histogram is empty
     */
    public long valueAtPercentile(final double percentile) {
        if (!(percentile >= 0 && percentile <= 100)) {
            throw new IllegalArgumentException("A percentile must be between 0 and 100, but is " + percentile);
        }
        checkNotEmpty();
        // The rank is computed in integers: in doubles, 99.9 / 100 * 1,000 is 999.0000000000001, so rank 1,000.
        final long ppm = Math.round(percentile * PPM_PER_PERCENT);
        if (ppm == 0) {
            return min;
        }
        if (ppm == PPM_PER_WHOLE) {
            return max;
        }
        final long rank = Math.ceilDiv(Math.multiplyExact(totalCount(), ppm), PPM_PER_WHOLE);
        long cumulative = 0;
        for (int i = 0; i < counts.length - 1; i++) {
            cumulative += counts[i];
            if (cumulative >= rank) {
                return Math.min(upperBound(i), max);
            }
        }
        return max;
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
     * Returns the number of buckets.
     *
     * @return the number of buckets
     */
    int bucketCount() {
        return counts.length;
    }

    /**
     * Returns the number of values in a bucket.
     *
     * @param index the bucket index
     * @return the number of values in the bucket
     */
    long countAt(final int index) {
        return counts[index];
    }

    private void checkNotEmpty() {
        // recorded values are never negative
        if (max < 0) {
            throw new IllegalStateException("The histogram is empty");
        }
    }
}
