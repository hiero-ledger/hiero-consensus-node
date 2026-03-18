// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.test.performance.benchmark.fixtures.MeasurementsCollector.Measurement;

/**
 * Parses {@code BenchmarkService} log entries from node logs.
 *
 * <p>This class is designed to work with the log output from {@code BenchmarkService}.
 * It extracts measurement data from log messages and allows running them through a {@link BenchmarkServiceLogEntryParser} to extract information out.
 *
 * <p>Usage:
 * <pre>{@code
 * Consumer<Something> list = new ArrayList();
 * BenchmarkServiceLogParser<Something> parser = Something::new;
 * BenchmarkServiceLogParser.parseFromLogs(network.newLogResults(), parser, list::add);
 * }</pre>
 */
public final class BenchmarkServiceLogParser {
    /**
     * Log prefix used for benchmark measurements. This prefix is used by {@link BenchmarkServiceLogParser}
     * to identify and parse benchmark log entries.
     */
    private static final String BENCHMARK_LOG_PREFIX = "BENCHMARK:";
    /**
     * Pattern to parse benchmark log messages.
     * Expected format: "BENCHMARK: nonce=123, latency=45μs, submissionTime=1234567890, handleTime=1234567935"
     * Supports units: ms, μs, us, ns
     */
    static final Pattern BENCHMARK_PATTERN = Pattern.compile(
            "nonce=(\\d+),\\s*latency=(-?\\d+)(ms|μs|us|ns|s),\\s*submissionTime=(\\d+),\\s*handleTime=(\\d+)");

    private BenchmarkServiceLogParser() {
        // Utility class
    }

    /**
     * Parses benchmark entries from all nodes' logs and passes them to a consumer.
     *
     * @param logResults the log results from all nodes
     * @param consumer the consumer to receive parsed entries
     */
    public static <T> void parseFromLogs(
            @NonNull final MultipleNodeLogResults logResults,
            @NonNull final BenchmarkServiceLogEntryParser<T> parser,
            @NonNull final Consumer<T> consumer) {
        for (final SingleNodeLogResult nodeResult : logResults.results()) {
            parseFromLogs(nodeResult, parser, consumer);
        }
    }

    /**
     * Parses log entries from a single node's logs and passes them to a consumer.
     *
     * @param logResult the log result from a single node
     * @param parser the log parser
     * @param consumer the consumer to receive parsed entries
     */
    public static <T> void parseFromLogs(
            @NonNull final SingleNodeLogResult logResult,
            @NonNull final BenchmarkServiceLogEntryParser<T> parser,
            @NonNull final Consumer<T> consumer) {

        final NodeId nodeId = logResult.nodeId();
        for (final StructuredLog logEntry : logResult.logs()) {
            // Only parse logs from BenchmarkService
            if (!logEntry.loggerName().endsWith("BenchmarkService")) {
                continue;
            }

            final String message = logEntry.message();
            if (!message.startsWith(BENCHMARK_LOG_PREFIX)) {
                continue;
            }

            final T entry = parser.apply(nodeId, logEntry);
            if (entry != null) {
                consumer.accept(entry);
            }
        }
    }

    /**
     * Parses a single log entry into a measurement.
     *
     * @param nodeId the node ID to associate with the entry
     * @param logEntry the structured log entry
     * @return the parsed measurement, or null if the log entry is not a benchmark log
     */
    @Nullable
    public static Measurement parseMeasurement(@Nullable final NodeId nodeId, @NonNull final StructuredLog logEntry) {
        final String message = logEntry.message();
        final Matcher matcher = BENCHMARK_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }

        try {
            final long nonce = Long.parseLong(matcher.group(1));
            final long latency = Long.parseLong(matcher.group(2));
            final TimeUnit unit = parseTimeUnit(matcher.group(3));
            final long submissionTime = Long.parseLong(matcher.group(4));
            final long handleTime = Long.parseLong(matcher.group(5));

            return new Measurement(nonce, latency, unit, submissionTime, handleTime, nodeId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @FunctionalInterface
    public interface BenchmarkServiceLogEntryParser<T> extends BiFunction<NodeId, StructuredLog, T> {}

    /**
     * Parses a time unit string from benchmark logs and returns the corresponding {@link TimeUnit}.
     *
     * @param unit the unit of time as a string (e.g., "ns", "us", "μs", "ms")
     * @return the corresponding {@link TimeUnit}
     * @throws IllegalArgumentException if the unit is not recognized
     */
    @NonNull
    public static TimeUnit parseTimeUnit(@NonNull final String unit) {
        return switch (unit) {
            case "ns" -> TimeUnit.NANOSECONDS;
            case "us", "μs" -> TimeUnit.MICROSECONDS;
            case "ms" -> TimeUnit.MILLISECONDS;
            case "s" -> TimeUnit.SECONDS;
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }
}
