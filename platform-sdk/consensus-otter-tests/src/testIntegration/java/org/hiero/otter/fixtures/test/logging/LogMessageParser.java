// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.otter.fixtures.integration.BaseIntegrationTest;

/**
 * Utility class for parsing log messages.
 */
public class LogMessageParser extends BaseIntegrationTest {

    /**
     * Pattern for parsing log messages.
     * Matches: [thread] [optional marker] LEVEL  logger - message
     * Group 1: marker (optional, e.g., STARTUP, PLATFORM_STATUS)
     * Group 2: log level (INFO, WARN, ERROR, etc.)
     * Group 3: logger name (e.g., org.hiero.otter.fixtures.internal.AbstractNetwork)
     */
    private static final Pattern LOG_PATTERN =
            Pattern.compile("\\[.*?]\\s+(?:\\[([^\\]]+)])?\\s*(\\w+)\\s+(\\S+)\\s+-");

    /**
     * Checks if a line matches the log message pattern.
     *
     * @param line the log line to check
     * @return {@code true} if the line matches the log message pattern, {@code false} otherwise
     * @throws NullPointerException if {@code line} is {@code null}
     */
    public static boolean isLogMessage(@NonNull final String line) {
        requireNonNull(line);
        return LOG_PATTERN.matcher(line).find();
    }

    /**
     * Extracts the marker from a log message line.
     * Returns empty string if the line doesn't match the expected pattern or if no marker is present.
     *
     * @param line the log line to extract the marker from
     * @return the extracted marker, or empty string if not found
     * @throws NullPointerException if {@code line} is {@code null}
     */
    @NonNull
    public static String extractMarker(@NonNull final String line) {
        requireNonNull(line);
        final Matcher matcher = LOG_PATTERN.matcher(line);
        if (matcher.find()) {
            final String marker = matcher.group(1);
            return marker != null ? marker : "";
        }
        return "";
    }

    /**
     * Extracts the log level from a log message line.
     * Returns empty string if the line doesn't match the expected pattern.
     *
     * @param line the log line to extract the log level from
     * @return the extracted log level, or empty string if not found
     * @throws NullPointerException if {@code line} is {@code null}
     */
    @NonNull
    public static String extractLogLevel(@NonNull final String line) {
        requireNonNull(line);
        final Matcher matcher = LOG_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(2) : "";
    }

    /**
     * Extracts the logger name from a log message line.
     * Returns empty string if the line doesn't match the expected pattern.
     *
     * @param line the log line to extract the logger name from
     * @return the extracted logger name, or empty string if not found
     * @throws NullPointerException if {@code line} is {@code null}
     */
    public static String extractLoggerName(final String line) {
        requireNonNull(line);
        final Matcher matcher = LOG_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(3) : "";
    }
}
