// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import java.time.Duration;
import java.util.Formatter;
import java.util.Locale;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;

/**
 * Represents the results of a network simulation run.
 *
 * <p>The creation-to-consensus latency (C2C) of every event that reached consensus is kept in a
 * {@link LatencyHistogram}. Its minimum, maximum and mean are exact; percentiles are precise to about 0.1%, and never
 * below the exact value. The reports have the same layout as those of the Falcon counterpart of this harness, so that
 * the output of both can be compared line by line.
 *
 * <p>The latency accessors that return a {@link Duration} return {@link Duration#ZERO} if no event reached consensus.
 * The histogram itself cannot be asked for statistics while it is empty.
 *
 * @param nodes amount of nodes for which
 * @param c2c the C2C of every event that reached consensus, in nanoseconds
 * @param eventsPerSec events per second
 * @param bytesPerSec bytes per second
 */
public record SimulationResult(int nodes, LatencyHistogram c2c, long eventsPerSec, long bytesPerSec) {

    /** The labels of the percentiles that are part of the reports, in the order of the {@link #PERCENTILES}. */
    private static final String[] PERCENTILE_LABELS = {"p50", "p90", "p99", "p99.9"};

    /** The percentiles that are part of the reports. */
    private static final double[] PERCENTILES = {50, 90, 99, 99.9};

    /**
     * Returns the number of events that reached consensus.
     *
     * @return the number of C2C values
     */
    public long c2cSamples() {
        return c2c.totalCount();
    }

    /**
     * Returns the lowest C2C.
     *
     * @return the lowest C2C, or {@link Duration#ZERO} if no event reached consensus
     */
    public Duration minC2C() {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos(c2c.min());
    }

    /**
     * Returns the mean C2C.
     *
     * @return the mean C2C, or {@link Duration#ZERO} if no event reached consensus
     */
    public Duration meanC2C() {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos((long) c2c.mean());
    }

    /**
     * Returns the highest C2C.
     *
     * @return the highest C2C, or {@link Duration#ZERO} if no event reached consensus
     */
    public Duration maxC2C() {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos(c2c.max());
    }

    /**
     * Returns the C2C that a given share of all events stayed at or below.
     *
     * @param percentile the percentile, between 0 and 100
     * @return the C2C at the percentile, or {@link Duration#ZERO} if no event reached consensus
     */
    public Duration c2cPercentile(final double percentile) {
        return c2cSamples() == 0 ? Duration.ZERO : Duration.ofNanos(c2c.valueAtPercentile(percentile));
    }

    /**
     * Returns the header of the CSV report written by {@link #toCsvRow(String)}. All latencies in the report are in
     * seconds.
     *
     * @param keyName the name of the first column, which identifies the row
     * @return the header line, with columns separated by semicolons
     */
    public static String csvHeader(final String keyName) {
        final StringBuilder header = new StringBuilder(keyName).append(";meanC2C");
        for (final String label : PERCENTILE_LABELS) {
            header.append(';').append(label).append("C2C");
        }
        return header.append(";maxC2C;events/s;bytes/s").toString();
    }

    /**
     * Returns this result as one line of a CSV report, with the columns of {@link #csvHeader(String)}. All latencies
     * are in seconds, at microsecond precision.
     *
     * @param key the value of the first column, which identifies the row
     * @return the row, with columns separated by semicolons
     */
    public String toCsvRow(final String key) {
        final StringBuilder row = new StringBuilder(key).append(';').append(seconds(meanC2C()));
        for (final double percentile : PERCENTILES) {
            row.append(';').append(seconds(c2cPercentile(percentile)));
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
     * Formats a duration as seconds at microsecond precision, always with a decimal point.
     *
     * @param duration the duration to format
     * @return the duration in seconds
     */
    public static String seconds(final Duration duration) {
        return String.format(Locale.ROOT, "%.6f", duration.toNanos() / 1_000_000_000.0);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final Formatter fmt = new Formatter(sb);
        fmt.format("Num nodes:    %d%n", nodes);
        fmt.format("C2C samples:  %,d%n", c2cSamples());
        fmt.format("C2C min:      %s%n", minC2C());
        fmt.format("C2C mean:     %s%n", meanC2C());
        for (int i = 0; i < PERCENTILES.length; i++) {
            fmt.format("C2C %-10s%s%n", PERCENTILE_LABELS[i] + ":", c2cPercentile(PERCENTILES[i]));
        }
        fmt.format("C2C max:      %s%n", maxC2C());
        fmt.format("Ev/sec:       %,d%n", eventsPerSec);
        fmt.format("Bytes/sec:    %,d%n", bytesPerSec);
        return sb.toString();
    }
}
