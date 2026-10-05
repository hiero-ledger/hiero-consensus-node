// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Collections;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.hiero.consensus.model.node.NodeId;

/**
 * The creation-to-consensus latency (C2C) and the event throughput of a Falcon network, measured in simulated time.
 *
 * <p>The C2C of an event, as observed by a node, is the time between the creation of the event and the moment that
 * node's consensus engine placed the event in a consensus round. Every node observes every event, so the network-wide
 * distribution contains one value per (node, event) pair.
 *
 * <p>The distribution is kept in {@link LatencyHistogram}s. The minimum, maximum and mean are exact; percentiles are
 * precise to about 0.1%, and never below the exact value. The histograms of a result are a snapshot: they must not be
 * modified, and later measurements do not change them.
 *
 * <p>The latency accessors that return a {@link Duration} return {@link Duration#ZERO} if no event reached consensus,
 * which happens when a network is measured before its first consensus round. The histograms themselves cannot be asked
 * for statistics while empty; check {@link LatencyHistogram#totalCount()} before reading them directly.
 *
 * @param nodes the number of nodes in the network
 * @param c2c the C2C of all (node, event) pairs of the whole network, in nanoseconds
 * @param eventsPerSec the number of events created by the whole network per second of simulated time
 * @param bytesPerSec the number of bytes of events created by the whole network per second of simulated time
 * @param c2cPerNode the C2C observed by each individual node, in nanoseconds, in node registration order
 */
public record ConsensusLatencyResult(
        int nodes,
        @NonNull LatencyHistogram c2c,
        long eventsPerSec,
        long bytesPerSec,
        @NonNull Map<NodeId, LatencyHistogram> c2cPerNode) {

    /** A percentile that is part of the CSV report, with the label of its column. */
    private record ReportedPercentile(@NonNull String label, double percentile) {}

    /** The percentiles that are part of the CSV report, in the order of its columns. */
    private static final List<ReportedPercentile> REPORTED_PERCENTILES = List.of(
            new ReportedPercentile("p50", 50),
            new ReportedPercentile("p90", 90),
            new ReportedPercentile("p99", 99),
            new ReportedPercentile("p99.9", 99.9));

    /**
     * Creates a new {@link ConsensusLatencyResult}.
     *
     * @throws NullPointerException if any of the non-primitive arguments is {@code null}
     */
    public ConsensusLatencyResult {
        requireNonNull(c2c);
        c2cPerNode = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(c2cPerNode)));
    }

    /**
     * Returns the number of C2C values, i.e. the number of (node, event) pairs that reached consensus.
     *
     * @return the number of C2C values
     */
    public long c2cSamples() {
        return c2c.totalCount();
    }

    /**
     * Returns the lowest C2C of any node and event.
     *
     * @return the lowest C2C, or {@link Duration#ZERO} if no event reached consensus
     */
    @NonNull
    public Duration minC2C() {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos(c2c.min());
    }

    /**
     * Returns the mean C2C over all nodes and events.
     *
     * @return the mean C2C, or {@link Duration#ZERO} if no event reached consensus
     */
    @NonNull
    public Duration meanC2C() {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos((long) c2c.mean());
    }

    /**
     * Returns the highest C2C of any node and event.
     *
     * @return the highest C2C, or {@link Duration#ZERO} if no event reached consensus
     */
    @NonNull
    public Duration maxC2C() {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos(c2c.max());
    }

    /**
     * Returns the C2C that a given share of all nodes and events stayed at or below. For example, the 99th percentile
     * is the C2C that 99% of the (node, event) pairs reached consensus within.
     *
     * @param percentile the percentile, between 0 and 100
     * @return the C2C at the percentile, or {@link Duration#ZERO} if no event reached consensus
     * @throws IllegalArgumentException if the percentile is not between 0 and 100
     */
    @NonNull
    public Duration c2cPercentile(final double percentile) {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos(c2c.valueAtPercentile(percentile));
    }

    /**
     * Returns the header of the CSV report written by {@link #toCsvRow(String)}. All latencies in the report are in
     * seconds.
     *
     * @param keyName the name of the first column, which identifies the row, for example the number of nodes
     * @return the header line, with columns separated by semicolons
     */
    @NonNull
    public static String csvHeader(@NonNull final String keyName) {
        final StringBuilder header = new StringBuilder(keyName).append(";meanC2C");
        for (final ReportedPercentile reported : REPORTED_PERCENTILES) {
            header.append(';').append(reported.label()).append("C2C");
        }
        return header.append(";maxC2C;events/s;bytes/s").toString();
    }

    /**
     * Returns this result as one line of a CSV report, with the columns of {@link #csvHeader(String)}. All latencies
     * are in seconds, at microsecond precision.
     *
     * @param key the value of the first column, which identifies the row, for example the number of nodes
     * @return the row, with columns separated by semicolons
     */
    @NonNull
    public String toCsvRow(@NonNull final String key) {
        final StringBuilder row = new StringBuilder(key).append(';').append(seconds(meanC2C()));
        for (final ReportedPercentile reported : REPORTED_PERCENTILES) {
            row.append(';').append(seconds(c2cPercentile(reported.percentile())));
        }
        return row.append(';')
                .append(seconds(maxC2C()))
                .append(';')
                .append(eventsPerSec)
                .append(';')
                .append(bytesPerSec)
                .toString();
    }

    /**
     * Formats a duration as seconds at microsecond precision, always with a decimal point, so that the result can be
     * used in a CSV report regardless of the locale.
     *
     * @param duration the duration to format
     * @return the duration in seconds
     */
    @NonNull
    public static String seconds(@NonNull final Duration duration) {
        return String.format(Locale.ROOT, "%.6f", duration.toNanos() / 1_000_000_000.0);
    }

    /**
     * Returns a multi-line summary of the result. The per-node distributions are omitted to keep the output compact.
     *
     * @return a human-readable summary
     */
    @Override
    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final Formatter fmt = new Formatter(sb);
        fmt.format("Num nodes:    %d%n", nodes);
        fmt.format("C2C samples:  %,d%n", c2cSamples());
        fmt.format("C2C min:      %s%n", minC2C());
        fmt.format("C2C mean:     %s%n", meanC2C());
        for (final ReportedPercentile reported : REPORTED_PERCENTILES) {
            fmt.format("C2C %-10s%s%n", reported.label() + ":", c2cPercentile(reported.percentile()));
        }
        fmt.format("C2C max:      %s%n", maxC2C());
        fmt.format("Ev/sec:       %,d%n", eventsPerSec);
        fmt.format("Bytes/sec:    %,d%n", bytesPerSec);
        return sb.toString();
    }
}
