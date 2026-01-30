// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * Collects benchmark measurements and computes statistics per node and in aggregate.
 *
 * <p>This class accumulates {@link Measurement} records grouped by node ID and computes
 * latency statistics including average, standard deviation, percentiles, and throughput.
 *
 * <p>Usage:
 * <pre>{@code
 * // After running benchmark transactions:
 * MeasurementsCollector collector = new MeasurementsCollector();
 * BenchmarkServiceLogParser.parseFromLogs(network.newLogResults(), Measurement::parse, collector::addEntry);
 * log.info(collector.generateReport());
 * }</pre>
 */
public class MeasurementsCollector {

    private static final Logger log = LogManager.getLogger(MeasurementsCollector.class);

    /**
     * T-values for 99.9% confidence interval (two-tailed, alpha=0.001).
     * Index corresponds to degrees of freedom (df). For df >= 120, use 3.291 (z-value).
     * Values from standard t-distribution tables.
     */
    private static final double[] T_VALUES_999 = {
        0.0, // df=0 (not used)
        636.619, // df=1
        31.599, // df=2
        12.924, // df=3
        8.610, // df=4
        6.869, // df=5
        5.959, // df=6
        5.408, // df=7
        5.041, // df=8
        4.781, // df=9
        4.587, // df=10
        4.437, // df=11
        4.318, // df=12
        4.221, // df=13
        4.140, // df=14
        4.073, // df=15
        4.015, // df=16
        3.965, // df=17
        3.922, // df=18
        3.883, // df=19
        3.850, // df=20
        3.819, // df=21
        3.792, // df=22
        3.768, // df=23
        3.745, // df=24
        3.725, // df=25
        3.707, // df=26
        3.690, // df=27
        3.674, // df=28
        3.659, // df=29
        3.646, // df=30
        3.591, // df=40 (approximated for 31-39)
        3.551, // df=50 (approximated for 41-49)
        3.520, // df=60 (approximated for 51-59)
        3.496, // df=70 (approximated for 61-69)
        3.476, // df=80 (approximated for 71-79)
        3.460, // df=90 (approximated for 81-89)
        3.446, // df=100 (approximated for 91-99)
    };

    private static final double T_VALUE_INFINITY = 3.291; // z-value for 99.9% CI

    // Per-node data
    private final Map<NodeId, List<Long>> latencySamplesByNode = new HashMap<>();
    private final Map<NodeId, Long> firstHandleTimeByNode = new HashMap<>();
    private final Map<NodeId, Long> lastHandleTimeByNode = new HashMap<>();
    private final Map<NodeId, Long> totalTransactionsByNode = new HashMap<>();
    private final Map<NodeId, Long> negativeLatencyByNode = new HashMap<>();

    // Unit tracking (set from first measurement)
    private String unit = null;

    /**
     * A single parsed benchmark measurement.
     *
     * @param nonce the transaction nonce
     * @param latency the measured latency value
     * @param unit the time unit (e.g., "ms", "μs", "ns")
     * @param submissionTime when the transaction was submitted
     * @param handleTime when the transaction was handled
     * @param nodeId the node that logged this measurement (may be null)
     */
    public record Measurement(
            long nonce, long latency, String unit, long submissionTime, long handleTime, NodeId nodeId) {

        /**
         * Pattern to parse benchmark log messages.
         * Expected format: "BENCHMARK: nonce=123, latency=45μs, submissionTime=1234567890, handleTime=1234567935"
         * Supports units: ms, μs, us, ns
         */
        private static final Pattern BENCHMARK_PATTERN = Pattern.compile(
                "nonce=(\\d+),\\s*latency=(-?\\d+)(ms|μs|us|ns),\\s*submissionTime=(\\d+),\\s*handleTime=(\\d+)");

        /**
         * Parses a single log entry into a benchmark entry.
         *
         * @param nodeId the node ID to associate with the entry
         * @param logEntry the structured log entry
         * @return the parsed benchmark entry, or null if the log entry is not a benchmark log
         */
        @Nullable
        public static Measurement parse(@Nullable final NodeId nodeId, @NonNull final StructuredLog logEntry) {
            final String message = logEntry.message();
            final Matcher matcher = BENCHMARK_PATTERN.matcher(message);
            if (!matcher.find()) {
                return null;
            }

            try {
                final long nonce = Long.parseLong(matcher.group(1));
                final long latency = Long.parseLong(matcher.group(2));
                final String unit = matcher.group(3);
                final long submissionTime = Long.parseLong(matcher.group(4));
                final long handleTime = Long.parseLong(matcher.group(5));

                return new Measurement(nonce, latency, unit, submissionTime, handleTime, nodeId);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse benchmark log entry: {}", message);
                return null;
            }
        }
    }

    /**
     * Adds a benchmark entry to the collection.
     *
     * @param entry the benchmark entry to add
     */
    public void addEntry(@NonNull final Measurement entry) {
        final NodeId nodeId = entry.nodeId();

        // Track unit from first measurement
        if (unit == null) {
            unit = entry.unit();
        }

        totalTransactionsByNode.merge(nodeId, 1L, Long::sum);

        if (entry.latency() < 0) {
            negativeLatencyByNode.merge(nodeId, 1L, Long::sum);
            log.warn(
                    DEMO_INFO.getMarker(),
                    "Negative latency detected: nonce={}, latency={}{} (submission={}, handle={})",
                    entry.nonce(),
                    entry.latency(),
                    entry.unit(),
                    entry.submissionTime(),
                    entry.handleTime());
            return;
        }

        latencySamplesByNode.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(entry.latency());

        firstHandleTimeByNode.compute(nodeId, (k, v) -> (v == null || entry.handleTime() < v) ? entry.handleTime() : v);
        lastHandleTimeByNode.compute(nodeId, (k, v) -> (v == null || entry.handleTime() > v) ? entry.handleTime() : v);
    }

    /**
     * Returns the set of node IDs that have recorded measurements.
     *
     * @return set of node IDs
     */
    @NonNull
    public Set<NodeId> getNodeIds() {
        return latencySamplesByNode.keySet();
    }

    /**
     * Returns the time unit used for measurements (e.g., "ms", "μs", "ns").
     *
     * @return the time unit, or "ms" if no measurements have been recorded
     */
    @NonNull
    public String getUnit() {
        return unit != null ? unit : "ms";
    }

    /**
     * Computes and returns statistics for a specific node.
     *
     * @param nodeId the node ID to compute statistics for
     * @return the computed statistics for that node
     */
    @NonNull
    public Statistics computeStatistics(@NonNull final NodeId nodeId) {
        final List<Long> samples = latencySamplesByNode.get(nodeId);
        if (samples == null || samples.isEmpty()) {
            return Statistics.EMPTY;
        }

        final long totalTx = totalTransactionsByNode.getOrDefault(nodeId, 0L);
        final long negativeTx = negativeLatencyByNode.getOrDefault(nodeId, 0L);
        final Long firstTime = firstHandleTimeByNode.get(nodeId);
        final Long lastTime = lastHandleTimeByNode.get(nodeId);

        return computeStats(samples, totalTx, negativeTx, firstTime, lastTime);
    }

    /**
     * Computes and returns aggregate statistics across all nodes.
     *
     * @return the computed aggregate statistics
     */
    @NonNull
    public Statistics computeStatistics() {
        final List<Long> allSamples = new ArrayList<>();
        long totalTx = 0;
        long negativeTx = 0;
        Long firstTime = null;
        Long lastTime = null;

        for (final NodeId nodeId : latencySamplesByNode.keySet()) {
            allSamples.addAll(latencySamplesByNode.get(nodeId));
            totalTx += totalTransactionsByNode.getOrDefault(nodeId, 0L);
            negativeTx += negativeLatencyByNode.getOrDefault(nodeId, 0L);

            final Long nodeFirst = firstHandleTimeByNode.get(nodeId);
            final Long nodeLast = lastHandleTimeByNode.get(nodeId);

            if (nodeFirst != null && (firstTime == null || nodeFirst < firstTime)) {
                firstTime = nodeFirst;
            }
            if (nodeLast != null && (lastTime == null || nodeLast > lastTime)) {
                lastTime = nodeLast;
            }
        }

        if (allSamples.isEmpty()) {
            return Statistics.EMPTY;
        }

        return computeStats(allSamples, totalTx, negativeTx, firstTime, lastTime);
    }

    private Statistics computeStats(
            final List<Long> samples,
            final long totalTx,
            final long negativeTx,
            final Long firstTime,
            final Long lastTime) {

        final int sampleCount = samples.size();
        final long[] sortedSamples =
                samples.stream().mapToLong(Long::longValue).sorted().toArray();

        final double average = computeAverage(sortedSamples);
        final double stdDev = computeStdDev(sortedSamples, average);

        // Calculate 99.9% confidence interval: error = t-value × (stdDev / √n)
        // This gives the half-width of the CI, so the true mean is likely in [avg - error, avg + error]
        final int degreesOfFreedom = sampleCount - 1;
        final double tValue = getTValue999(degreesOfFreedom);
        final double standardError = stdDev / Math.sqrt(sampleCount);
        final double error = tValue * standardError;

        final long min = sortedSamples[0];
        final long max = sortedSamples[sortedSamples.length - 1];
        final long p50 = percentile(sortedSamples, 50);
        final long p95 = percentile(sortedSamples, 95);
        final long p99 = percentile(sortedSamples, 99);

        final long durationMillis = (firstTime != null && lastTime != null) ? lastTime - firstTime : 0;
        final double throughput = (durationMillis > 0) ? (sampleCount * 1000.0 / durationMillis) : 0;

        return new Statistics(
                sampleCount,
                totalTx,
                negativeTx,
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
     * Generates a formatted report of the benchmark statistics in JMH-style format.
     * Includes per-node statistics and aggregate totals.
     *
     * @return the formatted report as a string
     */
    @NonNull
    public String generateReport() {
        final StringBuilder sb = new StringBuilder();
        final Statistics totalStats = computeStatistics();
        final String u = getUnit();

        if (totalStats.sampleCount() == 0) {
            sb.append("No benchmark data recorded.\n");
            return sb.toString();
        }

        // JMH-style header
        sb.append("\n");
        sb.append(String.format(
                "%-12s %6s %10s %10s %10s %8s %8s %8s %8s  %s%n",
                "Benchmark", "Cnt", "Score", "Error", "StdDev", "p50", "p95", "p99", "Max", "Units"));

        // Per-node results
        for (final NodeId nodeId : getNodeIds()) {
            final Statistics stats = computeStatistics(nodeId);
            appendJmhRow(sb, "Node " + nodeId, stats, u);
        }

        // Total
        appendJmhRow(sb, "TOTAL", totalStats, u);

        // Throughput summary
        sb.append("\n");
        sb.append(String.format(
                "Throughput: %.2f tx/s (over %d ms, %d samples)%n",
                totalStats.throughputPerSecond(), totalStats.durationMillis(), totalStats.sampleCount()));

        if (totalStats.negativeLatencyCount() > 0) {
            sb.append(String.format(
                    "Warning: %d measurements with negative latency%n", totalStats.negativeLatencyCount()));
        }

        // TSV for spreadsheet
        sb.append("\n");
        sb.append("# Copy below for spreadsheet (unit: ").append(u).append("):\n");
        sb.append("Node\tCnt\tAvg\tError(99.9%)\tStdDev\tp50\tp95\tp99\tMin\tMax\n");
        for (final NodeId nodeId : getNodeIds()) {
            final Statistics stats = computeStatistics(nodeId);
            appendTsvRow(sb, nodeId.toString(), stats);
        }
        appendTsvRow(sb, "TOTAL", totalStats);

        return sb.toString();
    }

    private void appendJmhRow(final StringBuilder sb, final String label, final Statistics stats, final String unit) {
        sb.append(String.format(
                "%-12s %6d %10.3f ±%9.3f %10.3f %8d %8d %8d %8d  %s/op%n",
                label,
                stats.sampleCount(),
                stats.average(),
                stats.error(),
                stats.stdDev(),
                stats.p50(),
                stats.p95(),
                stats.p99(),
                stats.max(),
                unit));
    }

    private void appendTsvRow(final StringBuilder sb, final String label, final Statistics stats) {
        sb.append(String.format(
                "%s\t%d\t%.3f\t%.3f\t%.3f\t%d\t%d\t%d\t%d\t%d%n",
                label,
                stats.sampleCount(),
                stats.average(),
                stats.error(),
                stats.stdDev(),
                stats.p50(),
                stats.p95(),
                stats.p99(),
                stats.min(),
                stats.max()));
    }

    private double computeAverage(final long[] samples) {
        long sum = 0;
        for (final long sample : samples) {
            sum += sample;
        }
        return (double) sum / samples.length;
    }

    private double computeStdDev(final long[] samples, final double avg) {
        if (samples.length < 2) {
            return 0.0;
        }
        double sumSquaredDiff = 0.0;
        for (final long sample : samples) {
            final double diff = sample - avg;
            sumSquaredDiff += diff * diff;
        }
        return Math.sqrt(sumSquaredDiff / (samples.length - 1));
    }

    private long percentile(final long[] sortedSamples, final int percentile) {
        if (sortedSamples.length == 1) {
            return sortedSamples[0];
        }
        final double index = (percentile / 100.0) * (sortedSamples.length - 1);
        final int lower = (int) Math.floor(index);
        final int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedSamples[lower];
        }
        final double fraction = index - lower;
        return Math.round(sortedSamples[lower] + fraction * (sortedSamples[upper] - sortedSamples[lower]));
    }

    /**
     * Returns the t-value for a 99.9% confidence interval given degrees of freedom.
     * Uses a lookup table for common values and falls back to the z-value (3.291) for large df.
     *
     * @param degreesOfFreedom the degrees of freedom (n - 1)
     * @return the t-value for 99.9% confidence
     */
    private double getTValue999(final int degreesOfFreedom) {
        if (degreesOfFreedom <= 0) {
            return T_VALUE_INFINITY;
        }
        if (degreesOfFreedom <= 30) {
            return T_VALUES_999[degreesOfFreedom];
        }
        if (degreesOfFreedom <= 40) {
            return T_VALUES_999[31]; // df=40
        }
        if (degreesOfFreedom <= 50) {
            return T_VALUES_999[32]; // df=50
        }
        if (degreesOfFreedom <= 60) {
            return T_VALUES_999[33]; // df=60
        }
        if (degreesOfFreedom <= 70) {
            return T_VALUES_999[34]; // df=70
        }
        if (degreesOfFreedom <= 80) {
            return T_VALUES_999[35]; // df=80
        }
        if (degreesOfFreedom <= 90) {
            return T_VALUES_999[36]; // df=90
        }
        if (degreesOfFreedom <= 100) {
            return T_VALUES_999[37]; // df=100
        }
        // For df > 100, t-distribution approaches normal distribution
        return T_VALUE_INFINITY;
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
}
