// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
public class SingleNodeLogResultAssert extends AbstractAssert<SingleNodeLogResultAssert, SingleNodeLogResult> {

    /**
     * Constructs an assertion for the given {@link SingleNodeLogResult}.
     *
     * @param actual the actual {@link SingleNodeLogResult} to assert
     */
    protected SingleNodeLogResultAssert(final SingleNodeLogResult actual) {
        super(actual, SingleNodeLogResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeLogResult}.
     *
     * @param actual the actual {@link SingleNodeLogResult} to assert
     * @return a new instance of {@link SingleNodeLogResultAssert}
     */
    public static SingleNodeLogResultAssert assertThat(final SingleNodeLogResult actual) {
        return new SingleNodeLogResultAssert(actual);
    }

    /**
     * Verifies that no log messages with the specified markers exist.
     *
     * @param first the first marker to check
     * @param rest additional markers to check
     */
    public void noMessageWithMarkers(@NonNull final LogMarker first, @NonNull final LogMarker... rest) {
        isNotNull();

        final Set<Marker> markers = Stream.concat(Stream.of(first), Arrays.stream(rest))
                .map(LogMarker::getMarker)
                .collect(Collectors.toSet());

        final List<Marker> foundMarkers = new ArrayList<>();
        final List<StructuredLog> foundLogs = new ArrayList<>();
        for (final Marker marker : markers) {
            if (actual.markers().contains(marker)) {
                foundMarkers.add(marker);
                final List<StructuredLog> messages = actual.logs().stream()
                        .filter(l -> Objects.equals(l.marker(), marker))
                        .toList();
                foundLogs.addAll(messages);
            }
        }
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
    }

    /**
     * Verifies that no log messages with a level higher than the specified level exist.
     *
     * @param level the maximum log level to allow
     */
    public void noMessageWithLeverHigherThan(@NonNull final Level level) {
        isNotNull();
        final List<StructuredLog> logs = actual.logs().stream()
                .filter(Objects::nonNull)
                .filter(l -> l.level().intLevel() < level.intLevel())
                .toList();
        if (!logs.isEmpty()) {
            final String message = String.format("Expected to find no message with lever higher than '%s'", level);
            failWithMessage(message, logs);
        }
    }

    /**
     * Fails the assertion with a custom message and the list of log entries that caused the failure.
     *
     * @param message the failure message
     * @param logs the list of {@link StructuredLog} entries that caused the failure
     */
    private void failWithMessage(@NonNull final String message, @NonNull final List<StructuredLog> logs) {
        final StringBuilder logStatements = new StringBuilder();
        logStatements.append("\n****************\n");
        logStatements.append(" ->  Log messages found:\n");
        logStatements.append("****************\n");
        logs.forEach(log -> logStatements.append(log.toString()));
        logStatements.append("****************\n");

        failWithMessage("%s %s", message, logStatements.toString());
    }
}
