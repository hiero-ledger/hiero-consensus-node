// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import static com.swirlds.cli.logging.LogLine.ERROR_LOG_LEVEL_COLOR;
import static com.swirlds.cli.logging.LogLine.HARMLESS_LOG_LEVEL_COLOR;
import static com.swirlds.cli.logging.LogLine.WARN_LOG_LEVEL_COLOR;

import com.swirlds.common.formatting.TextEffect;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Utility methods for processing log files into a more readable format.
 */
public class LogProcessingUtils {
    /**
     * Hidden constructor.
     */
    private LogProcessingUtils() {}

    /**
     * Get the correct color for a given log level.
     *
     * @param logLevel the log level
     * @return the color
     */
    @NonNull
    public static TextEffect getLogLevelColor(@NonNull final String logLevel) {
        return switch (logLevel) {
            case "TRACE", "DEBUG", "INFO" -> HARMLESS_LOG_LEVEL_COLOR;
            case "WARN" -> WARN_LOG_LEVEL_COLOR;
                // all other log levels are critical
            default -> ERROR_LOG_LEVEL_COLOR;
        };
    }

    /**
     * Parse a log timestamp string into an Instant.
     *
     * @param timestampString the timestamp string to parse
     * @param zoneId          the zone ID of the timestamp
     * @return the parsed Instant
     */
    public static Instant parseTimestamp(@NonNull final String timestampString, @NonNull final ZoneId zoneId) {
        return LocalDateTime.parse(timestampString, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                .atZone(zoneId)
                .toInstant();
    }

    /**
     * Generate an ANSI colorized version of a log line if the line can be parsed.
     * <p>
     * If the line cannot be parsed, it is returned without any colorization.
     *
     * @param inputString the input log line string
     * @param zoneId      the timezone of the timestamp in the log line
     * @return the colorized log line if it can be parsed, otherwise the original log line
     */
    @NonNull
    static String colorizeLogLineAnsi(@NonNull final String inputString, @NonNull final ZoneId zoneId) {
        try {
            final LogLine logLine = new LogLine(inputString, zoneId);

            return logLine.generateAnsiString();
        } catch (final Exception e) {
            return inputString;
        }
    }

    /**
     * Escapes the input string to be HTML safe
     *
     * @param inputString the string to escape
     * @return the escaped string
     */
    @NonNull
    public static String escapeString(@NonNull final String inputString) {
        return inputString
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("’", "&#39;");
    }
}
