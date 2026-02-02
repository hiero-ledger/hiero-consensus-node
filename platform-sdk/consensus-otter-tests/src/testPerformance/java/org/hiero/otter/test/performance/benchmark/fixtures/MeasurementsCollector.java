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
import org.hiero.otter.test.performance.benchmark.fixtures.StatisticsCalculator.Statistics;

/**
 * Collects benchmark measurements and computes statistics per node and in aggregate.
 *
 * <p>This class accumulates {@link Measurement} records grouped by node ID and delegates
 * statistics computation to {@link StatisticsCalculator}.
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

    // Per-node data
    private final Map<NodeId, List<Long>> latencySamplesByNode = new HashMap<>();
    private final Map<NodeId, Long> firstSubmissionTimeByNode = new HashMap<>();
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
         * Parses a single log entry into a measurement.
         *
         * @param nodeId the node ID to associate with the entry
         * @param logEntry the structured log entry
         * @return the parsed measurement, or null if the log entry is not a benchmark log
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
     * Adds a measurement to the collection.
     *
     * @param entry the measurement to add
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

        firstSubmissionTimeByNode.compute(
                nodeId, (k, v) -> (v == null || entry.submissionTime() < v) ? entry.submissionTime() : v);
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
     * @return the time unit, or "μs" if no measurements have been recorded
     */
    @NonNull
    public String getUnit() {
        return unit != null ? unit : "μs";
    }

    /**
     * Returns the number of time units per second based on the current measurement unit.
     *
     * @return conversion factor to convert from the measurement unit to seconds
     */
    private double getUnitsPerSecond() {
        return switch (getUnit()) {
            case "ms" -> 1_000.0;
            case "ns" -> 1_000_000_000.0;
            default -> 1_000_000.0;
        };
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

        final long total = totalTransactionsByNode.getOrDefault(nodeId, 0L);
        final long invalid = negativeLatencyByNode.getOrDefault(nodeId, 0L);
        final Long firstSubmissionTime = firstSubmissionTimeByNode.get(nodeId);
        final Long lastHandleTime = lastHandleTimeByNode.get(nodeId);

        return StatisticsCalculator.compute(samples, total, invalid, firstSubmissionTime, lastHandleTime);
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

            final Long nodeFirstSubmission = firstSubmissionTimeByNode.get(nodeId);
            final Long nodeLastHandle = lastHandleTimeByNode.get(nodeId);

            if (nodeFirstSubmission != null && (firstTime == null || nodeFirstSubmission < firstTime)) {
                firstTime = nodeFirstSubmission;
            }
            if (nodeLastHandle != null && (lastTime == null || nodeLastHandle > lastTime)) {
                lastTime = nodeLastHandle;
            }
        }

        if (allSamples.isEmpty()) {
            return Statistics.EMPTY;
        }

        return StatisticsCalculator.compute(allSamples, totalTx, negativeTx, firstTime, lastTime);
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
                "Throughput: %.2f tx/s (over %d %s, %d total transactions)%n",
                totalStats.throughput() * getUnitsPerSecond(),
                totalStats.duration(),
                u,
                totalStats.totalMeasurements()));

        if (totalStats.invalidMeasurements() > 0) {
            sb.append(String.format(
                    "Warning: %d measurements with negative latency%n", totalStats.invalidMeasurements()));
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
}
