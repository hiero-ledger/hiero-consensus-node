// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;

/**
 * Formats a {@link LogEvent} as a {@link String} and prints it to a given {@link Appendable}
 */
public class FormattedLinePrinter {

    private static final String THREAD_SUFFIX = "UNDEFINED-THREAD";
    private static final String LOGGER_SUFFIX = "UNDEFINED-LOGGER";
    private static final String UNDEFINED_MESSAGE = "UNDEFINED-MESSAGE";

    /**
     * The emergency logger.
     */
    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();

    private static final String LINE_SEPARATOR = System.lineSeparator();

    /**
     * Defines whether timestamps should be formatted as string or raw epoc values.
     */
    private final boolean formatTimestamp;

    /**
     * Creates a format
     *
     * @param formatTimestamp if true, timestamps will be converted to a human-readable format defined by
     *                        {@link TimestampPrinter}
     */
    public FormattedLinePrinter(boolean formatTimestamp) {
        this.formatTimestamp = formatTimestamp;
    }

    /**
     * Formats a {@link LogEvent} as a {@link String} and prints it to a given {@link Appendable}
     *
     * @param appendable Non-null appendable. Destination to write into.
     * @param event      Non-null event to write.
     */
    public void print(@NonNull final StringBuilder appendable, @NonNull final LogEvent event) {
        try {
            if (formatTimestamp) {
                TimestampPrinter.print(appendable, event.timestamp());
            } else {
                appendable.append(event.timestamp());
            }
            appendable.append(' ');
            appendable.append(event.level().nameWithFixedSize());
            appendable.append(" [");
            final String threadName = event.threadName();
            final String loggerName = event.loggerName();
            appendable.append(threadName != null ? threadName : THREAD_SUFFIX);
            appendable.append("] ");
            appendable.append(loggerName != null ? loggerName : LOGGER_SUFFIX);
            appendable.append(" - ");
            final LogMessage message = event.message();
            if (message != null) {
                appendable.append(message.getMessage());
            } else {
                appendable.append(UNDEFINED_MESSAGE);
                EMERGENCY_LOGGER.logNPE("message");
            }

            Marker marker = event.marker();
            if (marker != null) {
                appendable.append(" - [");
                appendable.append(String.join(", ", marker.getAllMarkerNames()));
                appendable.append("]");
            }

            final Map<String, String> context = event.context();
            if (context != null && !context.isEmpty()) {
                appendable.append(" - ");
                appendable.append(context);
            }

            appendable.append(LINE_SEPARATOR);

            Throwable throwable = event.throwable();
            if (throwable != null) {
                StackTracePrinter.print(appendable, throwable);
            }

        } catch (final Throwable e) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to format and print event", e);
        }
    }

    /**
     * Creates in instance of {@link FormattedLinePrinter}
     *
     * @throws NullPointerException if any of the arguments is {@code null}
     */
    public static @NonNull FormattedLinePrinter createForHandler(
            @NonNull final String handlerName, @NonNull final Configuration configuration) {
        Objects.requireNonNull(handlerName, "handlerName must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        final String formatTimestampKey = "logging.handler." + handlerName + ".formatTimestamp";
        final Boolean formatTimestamp = configuration.getValue(formatTimestampKey, Boolean.class, true);
        return new FormattedLinePrinter(formatTimestamp != null && formatTimestamp);
    }
}
