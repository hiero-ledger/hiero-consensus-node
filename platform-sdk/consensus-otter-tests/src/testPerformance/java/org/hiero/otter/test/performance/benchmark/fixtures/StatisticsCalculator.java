// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;

/**
 * Calculates statistical metrics for benchmark measurements.
 *
 * <p>This class provides methods to compute various statistics including:
 * <ul>
 *   <li>Average and standard deviation</li>
 *   <li>Percentiles (p50, p95, p99)</li>
 *   <li>99.9% confidence interval (requires at least 100 samples)</li>
 *   <li>Throughput calculations</li>
 * </ul>
 *
 * <p>All methods are stateless and operate on the provided data.
 */
public final class StatisticsCalculator {

    /**
     * Minimum number of samples required to compute the confidence interval error.
     * With fewer samples, the t-distribution differs significantly from the normal
     * distribution, making the z-value approximation inaccurate.
     */
    private static final int MIN_SAMPLES_FOR_ERROR = 100;

    /** Z-value for 99.9% confidence interval (two-tailed, alpha=0.001). */
    private static final double Z_VALUE_999 = 3.291;

    private StatisticsCalculator() {
        // Utility class - prevent instantiation
    }

    /**
     * Immutable statistics snapshot.
     *
     * @param sampleCount number of valid latency samples
     * @param totalTransactions total benchmark transactions seen
     * @param negativeLatencyCount transactions with negative latency
     * @param average average latency
     * @param stdDev standard deviation
     * @param error 99.9% confidence interval half-width
     * @param min minimum latency
     * @param max maximum latency
     * @param p50 50th percentile (median)
     * @param p95 95th percentile
     * @param p99 99th percentile
     * @param durationMillis total duration from first to last transaction in milliseconds
     * @param throughputPerSecond transactions per second
     */
    public record Statistics(
            int sampleCount,
            long totalTransactions,
            long negativeLatencyCount,
            double average,
            double stdDev,
            double error,
            long min,
            long max,
            long p50,
            long p95,
            long p99,
            long durationMillis,
            double throughputPerSecond) {

        /** Empty statistics for when no samples have been collected. */
        public static final Statistics EMPTY = new Statistics(0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0.0);
    }

    /**
     * Computes comprehensive statistics from the given samples and metadata.
     *
     * @param samples list of latency samples (will be sorted in place)
     * @param total total number of attempted measurements
     * @param invalid number of invalid measurements
     * @param firstTime timestamp of the first transaction (nullable)
     * @param lastTime timestamp of the last transaction (nullable)
     * @return computed statistics
     */
    @NonNull
    public static Statistics compute(
            @NonNull final List<Long> samples,
            final long total,
            final long invalid,
            final Long firstTime,
            final Long lastTime) {

        if (samples.isEmpty()) {
            return Statistics.EMPTY;
        }

        final int sampleCount = samples.size();
        Collections.sort(samples);

        final double average = computeAverage(samples);
        final double stdDev = computeStdDev(samples, average);

        // Calculate 99.9% confidence interval: error = z-value × (stdDev / sqrt(n))
        // Only computed with 100+ samples where z-approximation is accurate.
        // This gives the half-width of the confidence interval,
        // so the true mean is likely in [avg - error, avg + error]
        final double error;
        if (sampleCount >= MIN_SAMPLES_FOR_ERROR) {
            final double standardError = stdDev / Math.sqrt(sampleCount);
            error = Z_VALUE_999 * standardError;
        } else {
            error = 0.0;
        }

        final long min = samples.getFirst();
        final long max = samples.getLast();
        final long p50 = percentile(samples, 50);
        final long p95 = percentile(samples, 95);
        final long p99 = percentile(samples, 99);

        final long durationMillis = (firstTime != null && lastTime != null) ? lastTime - firstTime : 0;
        final double throughput = (durationMillis > 0) ? (sampleCount * 1000.0 / durationMillis) : 0;

        return new Statistics(
                sampleCount,
                total,
                invalid,
                average,
                stdDev,
                error,
                min,
                max,
                p50,
                p95,
                p99,
                durationMillis,
                throughput);
    }

    /**
     * Computes the arithmetic mean of the samples.
     *
     * @param samples list of samples
     * @return the average value
     */
    public static double computeAverage(@NonNull final List<Long> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        long sum = 0;
        for (final long sample : samples) {
            sum += sample;
        }
        return (double) sum / samples.size();
    }

    /**
     * Computes the sample standard deviation.
     *
     * @param samples list of samples
     * @param average the pre-computed average
     * @return the standard deviation
     */
    public static double computeStdDev(@NonNull final List<Long> samples, final double average) {
        if (samples.size() < 2) {
            return 0.0;
        }
        double sumSquaredDiff = 0.0;
        for (final long sample : samples) {
            final double diff = sample - average;
            sumSquaredDiff += diff * diff;
        }
        return Math.sqrt(sumSquaredDiff / (samples.size() - 1));
    }

    /**
     * Computes the specified percentile from sorted samples.
     *
     * @param sortedSamples list of samples (must be sorted in ascending order)
     * @param percentile the percentile to compute (0-100)
     * @return the percentile value
     */
    public static long percentile(@NonNull final List<Long> sortedSamples, final int percentile) {
        if (sortedSamples.isEmpty()) {
            return 0;
        }
        if (sortedSamples.size() == 1) {
            return sortedSamples.getFirst();
        }
        final double index = (percentile / 100.0) * (sortedSamples.size() - 1);
        final int lower = (int) Math.floor(index);
        final int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedSamples.get(lower);
        }
        final double fraction = index - lower;
        return Math.round(sortedSamples.get(lower) + fraction * (sortedSamples.get(upper) - sortedSamples.get(lower)));
    }
}
