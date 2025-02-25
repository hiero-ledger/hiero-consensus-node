// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import java.time.Duration;

/**
 * Captures statistics about a particular part of the benchmark.
 */
public class BenchmarkStatistic {

    private long totalTimeNanoseconds;
    private long totalOperationCount;

    private long startTimeNanoseconds;

    private final Duration testDuration;

    private final String name;

    public BenchmarkStatistic(final String name, final Duration testDuration) {
        this.name = name;
        this.testDuration = testDuration;
    }

    /**
     * Call this when the operation starts.
     */
    public void start() {
        startTimeNanoseconds = System.nanoTime();
    }

    /**
     * Call this when the operation finishes.
     */
    public void stop() {
        totalTimeNanoseconds += (System.nanoTime() - startTimeNanoseconds);
        totalOperationCount++;
    }

    /**
     * Get the average time required to perform this operation.
     */
    public Duration getAverageLatency() {
        if (totalOperationCount == 0) {
            return Duration.ofNanos(-1);
        }
        return Duration.ofNanos(totalTimeNanoseconds / totalOperationCount);
    }

    /**
     * Get the average number of operations per second.
     */
    public double getAverageThroughput() {
        return ((double) totalOperationCount) / testDuration.toSeconds();
    }

    /**
     * Get the total number of times this operation was performed.
     */
    public long getCount() {
        return totalOperationCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(name).append(":\n");
        sb.append("    total number of operations: ")
                .append(totalOperationCount)
                .append("\n");
        sb.append("    latency: ")
                .append(humanReadableDuration(getAverageLatency()))
                .append("\n");
        sb.append("    throughput: ")
                .append(String.format("%.1f", getAverageThroughput()))
                .append(" hz\n");

        return sb.toString();
    }

    /**
     * Convert a duration to a human readable value.
     */
    private static String humanReadableDuration(final Duration duration) {
        String unit = "ns";
        double value = duration.getNano();

        if (value > 1000) {
            value /= 1000;
            unit = "us";
        }

        if (value > 1000) {
            value /= 1000;
            unit = "ms";
        }

        if (value > 1000) {
            value /= 1000;
            unit = "s";
        }

        return String.format("%.1f %s", value, unit);
    }
}
