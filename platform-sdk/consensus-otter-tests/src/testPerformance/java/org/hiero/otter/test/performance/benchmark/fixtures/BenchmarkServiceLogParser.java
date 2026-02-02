// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Parses {@code BenchmarkService} log entries from node logs.
 *
 * <p>This class is designed to work with the log output from {@code BenchmarkService}.
 * It extracts measurement data from log messages and allows running them through a {@link BenchmarkServiceLogEntryParser} to extract information out.
 *
 * <p>Usage:
 * <pre>{@code
 * // Parse all benchmark entries from logs:
 * BenchmarkServiceLogEntryParser<Something> parser = new BenchmarkServiceLogEntryParser<Something>{...};
 * List<Something> entries = BenchmarkLogParser.parseFromLogs(network.newLogResults(), parser);
 *
 * // Or use with a consumer:
 * Consumer<Something> list = new ArrayList();
 * BenchmarkServiceLogEntryParser<Something> parser = Something::new;
 * BenchmarkLogParser.parseFromLogs(network.newLogResults(), parser, list::add);
 * }</pre>
 */
public final class BenchmarkServiceLogParser {
    /**
     * Log prefix used for benchmark measurements. This prefix is used by {@link BenchmarkServiceLogParser}
     * to identify and parse benchmark log entries.
     */
    private static final String BENCHMARK_LOG_PREFIX = "BENCHMARK:";

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

    @FunctionalInterface
    public interface BenchmarkServiceLogEntryParser<T> extends BiFunction<NodeId, StructuredLog, T> {}
}
