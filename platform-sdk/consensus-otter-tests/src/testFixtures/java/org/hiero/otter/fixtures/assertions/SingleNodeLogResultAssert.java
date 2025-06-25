// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Assertion class for {@link SingleNodeLogResult}.
 *
 * <p>Provides custom assertions for validating log results of a single node.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeLogResultAssert extends AbstractAssert<SingleNodeLogResultAssert, SingleNodeLogResult> {

    /**
     * Constructs an assertion for the given {@link SingleNodeLogResult}.
     *
     * @param actual the actual {@link SingleNodeLogResult} to assert
     */
    protected SingleNodeLogResultAssert(@Nullable final SingleNodeLogResult actual) {
        super(actual, SingleNodeLogResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeLogResult}.
     *
     * @param actual the actual {@link SingleNodeLogResult} to assert
     * @return a new instance of {@link SingleNodeLogResultAssert}
     */
    @NonNull
    public static SingleNodeLogResultAssert assertThat(@Nullable final SingleNodeLogResult actual) {
        return new SingleNodeLogResultAssert(actual);
    }

    /**
     * Verifies that no log messages with the specified markers exist.
     *
     * @param first the first marker to check
     * @param rest additional markers to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeLogResultAssert hasNoMessagesWithMarkers(
            @NonNull final LogMarker first, @Nullable final LogMarker... rest) {
        isNotNull();

        final Stream<LogMarker> restStream = rest == null ? Stream.empty() : Arrays.stream(rest);
        final Set<Marker> markers = Stream.concat(Stream.of(first), restStream).toList().stream()
                .map(LogMarker::getMarker)
                .collect(Collectors.toSet());

        final List<StructuredLog> foundLogs = actual.logs().stream()
                .filter(log -> markers.contains(log.marker()))
                .toList();
        final Set<Marker> foundMarkers =
                foundLogs.stream().map(StructuredLog::marker).collect(Collectors.toSet());

        final StringBuilder message = new StringBuilder();
        message.append("Expected to find no message with marker");
        foundMarkers.forEach(marker -> {
            message.append(" '");
            message.append(marker.toString());
            message.append("',");
        });
        message.deleteCharAt(message.length() - 1);
        message.append(" but found:");
        failWithMessage(message.toString(), foundLogs);

        return this;
    }

    /**
     * Verifies that no log messages with a level higher than the specified level exist.
     *
     * @param level the maximum log level to allow
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeLogResultAssert hasNoMessagesWithLevelHigherThan(@NonNull final Level level) {
        isNotNull();
        final List<StructuredLog> logs = actual.logs().stream()
                .filter(log -> log.level().intLevel() < level.intLevel())
                .toList();
        if (!logs.isEmpty()) {
            final String message = String.format("Expected to find no message with lever higher than '%s'", level);
            failWithMessage(message, logs);
        }
        return this;
    }

    /**
     * Verifies that no log messages with an error log level exist.
     *
     * @return this assertion object for method chaining
     */
    public SingleNodeLogResultAssert hasNoErrorLevelMessages() {
        return hasNoMessagesWithLevelHigherThan(Level.WARN);
    }

    /**
     * Fails the assertion with a custom message and the list of log entries that caused the failure.
     *
     * @param message the failure message
     * @param logs the list of {@link StructuredLog} entries that caused the failure
     */
    private void failWithMessage(@NonNull final String message, @NonNull final List<StructuredLog> logs) {
        final StringBuilder logStatements = new StringBuilder();
        logStatements.append(message);
        logStatements.append("\n****************\n");
        logStatements.append(" ->  Log messages found:\n");
        logStatements.append("****************\n");
        logs.forEach(log -> logStatements.append(log.toString()));
        logStatements.append("****************\n");

        failWithMessage(logStatements.toString());
    }
}
