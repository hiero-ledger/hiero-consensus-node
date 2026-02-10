// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
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
 *  // ...
 *      collector.addEntry( new Measurement(...));
 *  // ...
 * log.info(collector.generateReport());
 * }</pre>
 */
public class MeasurementsCollector {

    private static final Logger log = LogManager.getLogger(MeasurementsCollector.class);
    private static final String UNIT = "μs";

    // Per-node data
    private final Map<NodeId, List<Long>> latencySamplesByNode = new HashMap<>();
    private final Map<NodeId, Long> firstSubmissionTimeByNodeInUs = new HashMap<>();
    private final Map<NodeId, Long> lastHandleTimeByNodeInUs = new HashMap<>();
    private final Map<NodeId, Long> totalTransactionsByNode = new HashMap<>();
    private final Map<NodeId, Long> negativeLatencyByNode = new HashMap<>();

    /**
     * A single parsed benchmark measurement.
     *
     * @param nonce the transaction nonce
     * @param latency the measured latency value
     * @param unit the time unit.
     * @param submissionTime when the transaction was submitted
     * @param handleTime when the transaction was handled
     * @param nodeId the node that logged this measurement (may be null)
     */
    public record Measurement(
            long nonce, long latency, TimeUnit unit, long submissionTime, long handleTime, NodeId nodeId) {}

    /**
     * Adds a measurement to the collection.
     *
     * @param entry the measurement to add
     */
    public void addEntry(@NonNull final Measurement entry) {
        final NodeId nodeId = entry.nodeId();

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

        final var timeUnit = entry.unit();
        latencySamplesByNode.computeIfAbsent(nodeId, k -> new ArrayList<>()).add((timeUnit.toMicros(entry.latency())));

        final long submissionTimeMicros = timeUnit.toMicros(entry.submissionTime());
        final long handleTimeMicros = timeUnit.toMicros(entry.handleTime());

        firstSubmissionTimeByNodeInUs.compute(
                nodeId, (k, v) -> (v == null || submissionTimeMicros < v) ? submissionTimeMicros : v);
        lastHandleTimeByNodeInUs.compute(nodeId, (k, v) -> (v == null || handleTimeMicros > v) ? handleTimeMicros : v);
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
        final Long firstSubmissionTime = firstSubmissionTimeByNodeInUs.get(nodeId);
        final Long lastHandleTime = lastHandleTimeByNodeInUs.get(nodeId);

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
        long totalSamples = 0;
        long invalidSamples = 0;
        Long firstTime = null;
        Long lastTime = null;

        for (final NodeId nodeId : latencySamplesByNode.keySet()) {
            allSamples.addAll(latencySamplesByNode.get(nodeId));
            totalSamples += totalTransactionsByNode.getOrDefault(nodeId, 0L);
            invalidSamples += negativeLatencyByNode.getOrDefault(nodeId, 0L);

            final Long nodeFirstSubmission = firstSubmissionTimeByNodeInUs.get(nodeId);
            final Long nodeLastHandle = lastHandleTimeByNodeInUs.get(nodeId);

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

        return StatisticsCalculator.compute(allSamples, totalSamples, invalidSamples, firstTime, lastTime);
    }

    /**
     * Generates a formatted report of the benchmark statistics in JMH-style format.
     * Includes per-node statistics and aggregate totals.
     *
     * @return the formatted report as a string
     */
    @NonNull
    public String generateReport() {
        final StringBuilder jmhStyleReport = new StringBuilder();
        final Statistics totalStats = computeStatistics();

        if (totalStats.sampleCount() == 0) {
            jmhStyleReport.append("No benchmark data recorded.\n");
            return jmhStyleReport.toString();
        }

        final org.hiero.base.utility.StringTable table = org.hiero.base.utility.StringTable.column("Benchmark")
                .column("Cnt")
                .column("Score")
                .typeFloat(10, 3)
                .headlessColumn()
                .withFixedValue(UNIT)
                .headlessColumn()
                .fixedValue("±")
                .column("Error")
                .typeFloat(6, 3)
                .headlessColumn()
                .withFixedValue(UNIT)
                .column("StdDev")
                .typeFloat(6, 3)
                .headlessColumn()
                .withFixedValue(UNIT)
                .column("p50")
                .typeInt(6)
                .headlessColumn()
                .withFixedValue(UNIT)
                .column("p95")
                .typeInt(6)
                .headlessColumn()
                .withFixedValue(UNIT)
                .column("p99")
                .typeInt(6)
                .headlessColumn()
                .withFixedValue(UNIT)
                .column("Max")
                .typeInt(6)
                .headlessColumn()
                .withFixedValue(UNIT)
                .column("Throughput")
                .typeFloat(6, 3)
                .headlessColumn()
                .fixedValue("ops/s")
                .build();

        jmhStyleReport.append("\n");

        // Per-node results
        for (final NodeId nodeId : getNodeIds()) {
            final Statistics stats = computeStatistics(nodeId);
            table.addRow(
                    "Node " + nodeId,
                    stats.sampleCount(),
                    stats.average(),
                    stats.error(),
                    stats.stdDev(),
                    stats.p50(),
                    stats.p95(),
                    stats.p99(),
                    stats.max(),
                    stats.throughput());
        }

        table.addRow(
                "TOTAL",
                totalStats.sampleCount(),
                totalStats.average(),
                totalStats.error(),
                totalStats.stdDev(),
                totalStats.p50(),
                totalStats.p95(),
                totalStats.p99(),
                totalStats.max(),
                totalStats.throughput());
        // Total
        jmhStyleReport.append(table);
        // Throughput summary
        jmhStyleReport.append("\n");
        jmhStyleReport.append(String.format(
                "Test throughput: %.2f tx/s (over %d %s, %d total transactions)%n",
                totalStats.throughput(), totalStats.duration(), UNIT, totalStats.totalMeasurements()));

        if (totalStats.invalidMeasurements() > 0) {
            jmhStyleReport.append(String.format(
                    "Warning: %d measurements with negative latency%n", totalStats.invalidMeasurements()));
        }
        jmhStyleReport.append("\n");
        // TSV for spreadsheet
        final String tsvStyleReport = "# Copy below for spreadsheet (unit: "
                + UNIT
                + "):\n"
                + "Avg\tError(99.9%)\tStdDev\tp50\tp95\tp99\tMin\tMax\n"
                + String.format(
                        "%.3f\t%.3f\t%.3f\t%d\t%d\t%d\t%d\t%d%n",
                        totalStats.average(),
                        totalStats.error(),
                        totalStats.stdDev(),
                        totalStats.p50(),
                        totalStats.p95(),
                        totalStats.p99(),
                        totalStats.min(),
                        totalStats.max());

        return jmhStyleReport + "\n" + tsvStyleReport;
    }
}
