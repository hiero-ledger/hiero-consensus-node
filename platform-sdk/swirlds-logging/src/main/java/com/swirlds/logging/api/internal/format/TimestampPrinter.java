// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.format.DateTimeFormatter;

/**
 * Utility class to help formatting epoc milliseconds like those coming from ({@link System#currentTimeMillis()}) to a
 * String representation matching  {@code "yyyy-MM-dd HH:mm:ss.SSS"}
 */
public class TimestampPrinter {

    /**
     * The emergency logger.
     */
    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();

    /**
     * Space filler values, so we can return a fixed size string
     */
    private static final String[] PADDING_VALUES = preparePaddingValues();

    /**
     * The formatter for the timestamp.
     */
    private static final EpochCachedFormatter FORMATTER = new EpochCachedFormatter();

    private static final String BROKEN_TIMESTAMP = "BROKEN-TIMESTAMP";
    private static final int DATE_FIELD_MAX_SIZE = 26;

    private TimestampPrinter() {}

    /**
     * Returns the String representation matching {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME} for the epoc value
     * {@code timestamp}
     */
    public static void print(final @NonNull StringBuilder writer, final long timestamp) {
        String format;
        try {
            format = FORMATTER.format(timestamp);

        } catch (final Throwable e) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to format instant", e);
            format = BROKEN_TIMESTAMP;
        }
        writer.append(format);
        writer.append(PADDING_VALUES[DATE_FIELD_MAX_SIZE - format.length()]);
    }

    /**
     * Prepares an array of whitespace padding values with varying lengths of whitespace strings.
     * <p>
     * This method initializes and populates an array of strings with whitespace fillers.
     * The length of each whitespace filler string corresponds to its index in the array.
     * The first element is an empty string, and subsequent elements contain increasing numbers of spaces.
     * The length of the array is determined by the constant {@code DATE_FIELD_MAX_SIZE} plus one.
     * The method returns the array of fillers.
     *
     * @return An array of whitespace fillers with varying lengths.
     */
    private static @NonNull String[] preparePaddingValues() {
        final String[] fillers = new String[DATE_FIELD_MAX_SIZE + 1];
        fillers[0] = "";
        for (int i = 1; i <= DATE_FIELD_MAX_SIZE; i++) {
            fillers[i] = " ".repeat(i);
        }
        return fillers;
    }
}
