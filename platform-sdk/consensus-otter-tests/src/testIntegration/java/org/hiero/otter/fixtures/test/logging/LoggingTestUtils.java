// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test.logging;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.logging.legacy.LogMarker.PLATFORM_STATUS;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager.Log4jMarker;

/**
 * Utility class for testing logging.
 */
public class LoggingTestUtils {

    /** List of markers that commonly appear during normal Container node operation. */
    public static final Set<Marker> MARKERS_APPEARING_IN_NORMAL_OPERATION = Set.of(
            STARTUP.getMarker(),
            PLATFORM_STATUS.getMarker(),
            STATE_TO_DISK.getMarker(),
            MERKLE_DB.getMarker(),
            SOCKET_EXCEPTIONS.getMarker(),
            RECONNECT.getMarker());

    /** Log levels that commonly appear during normal node operation. */
    public static final List<Level> LOG_LEVELS_APPEARING_IN_NORMAL_OPERATION = List.of(Level.WARN, Level.INFO);

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
    @Nullable
    public static Marker extractMarker(@NonNull final String line) {
        requireNonNull(line);
        final Matcher matcher = LOG_PATTERN.matcher(line);
        if (matcher.find()) {
            final String marker = matcher.group(1);
            return marker != null ? new Log4jMarker(marker) : null;
        }
        return null;
    }

    /**
     * Extracts the log level from a log message line.
     * Returns empty string if the line doesn't match the expected pattern.
     *
     * @param line the log line to extract the log level from
     * @return the extracted log level, or empty string if not found
     * @throws NullPointerException if {@code line} is {@code null}
     */
    @Nullable
    public static Level extractLogLevel(@NonNull final String line) {
        requireNonNull(line);
        final Matcher matcher = LOG_PATTERN.matcher(line);
        return matcher.find() ? Level.getLevel(matcher.group(2)) : null;
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

    /**
     * Checks if a log line contains only regular markers that appear during normal operation.
     *
     * @param logLine the log line to check
     * @return {@code true} if the log line contains only regular markers, {@code false} otherwise
     */
    public static boolean lineHasRegularMarkersOnly(@NonNull final String logLine) {
        final Marker marker = extractMarker(logLine);
        return marker == null || MARKERS_APPEARING_IN_NORMAL_OPERATION.contains(marker);
    }

    /**
     * Checks if a log line contains only the STATE_HASH marker or no marker at all.
     *
     * @param logLine the log line to check
     * @return {@code true} if the log line contains only the STATE_HASH marker or no marker, {@code false} otherwise
     */
    public static boolean lineHasStateHashMarkerOnly(@NonNull final String logLine) {
        final Marker marker = extractMarker(logLine);
        return marker == null || STATE_HASH.getMarker().equals(marker);
    }

    /**
     * Checks if a log line has one of the allowed log levels.
     *
     * @param logLine the log line to check
     * @param allowedLevels the allowed log levels
     * @return {@code true} if the log line has one of the allowed log levels, {@code false} otherwise
     */
    public static boolean lineHasLogLevels(@NonNull final String logLine, @NonNull final Level... allowedLevels) {
        final Level level = extractLogLevel(logLine);
        return level == null || List.of(allowedLevels).contains(level);
    }

    /**
     * Waits for a file to exist and have non-zero size, with a timeout.
     *
     * @param file the file to wait for
     * @param timeout the maximum time to wait
     */
    public static void awaitFile(@NonNull final Path file, @NonNull final Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> assertThat(file)
                .isNotEmptyFile());
    }
}
