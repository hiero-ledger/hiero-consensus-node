// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_RIGHT;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Various utilities for formatting strings.
 */
public final class StringFormattingUtils {

    private StringFormattingUtils() {}

    /**
     * Write the provided string to the string builder, followed by a line separator.
     *
     * @param sb
     * 		a string builder to write to
     * @param line
     * 		the line to add
     */
    public static void addLine(final StringBuilder sb, final String line) {
        sb.append(line).append(System.lineSeparator());
    }

    /**
     * Write a comma separated list to a string builder.
     *
     * @param sb
     * 		a string builder to write the list to
     * @param iterator
     * 		the objects returned by this iterator will be written to the formatted list
     */
    public static void formattedList(final StringBuilder sb, final Iterator<?> iterator) {
        formattedList(sb, iterator, ", ");
    }

    /**
     * Write a formatted list to a string builder.
     *
     * @param sb
     * 		a string builder to write the list to
     * @param iterator
     * 		the objects returned by this iterator will be written to the formatted list
     * @param separator
     * 		the element between list items, e.g. ", "
     */
    public static void formattedList(final StringBuilder sb, final Iterator<?> iterator, final String separator) {
        while (iterator.hasNext()) {
            sb.append(iterator.next());
            if (iterator.hasNext()) {
                sb.append(separator);
            }
        }
    }

    /**
     * Build a comma separated list.
     *
     * @param iterator
     * 		the objects returned by this iterator will be written to the formatted list
     * @return a comma separated list
     */
    public static String formattedList(final Iterator<?> iterator) {
        final StringBuilder sb = new StringBuilder();
        formattedList(sb, iterator, ", ");
        return sb.toString();
    }

    /**
     * Build a comma separated list.
     *
     * @param iterator
     * 		the objects returned by this iterator will be written to the formatted list
     * @param separator
     * 		the element between list items, e.g. ", "
     * @return a comma separated list
     */
    public static String formattedList(final Iterator<?> iterator, final String separator) {
        final StringBuilder sb = new StringBuilder();
        formattedList(sb, iterator, separator);
        return sb.toString();
    }

    /**
     * Repeat a char a bunch of times, forming a string. This method is useful for repeating chars, which,
     * unlike String, do not give us a convenient method for repeating them.
     */
    public static String repeatedChar(final char c, final int count) {
        return String.valueOf(c).repeat(Math.max(0, count));
    }

    /**
     * Format a number into a comma-separated string.
     *
     * @param value
     * 		the value to format
     */
    public static void commaSeparatedNumber(final StringBuilder sb, final long value) {
        if (value == 0) {
            sb.append("0");
            return;
        }

        long runningValue = value;
        if (value < 0) {
            sb.append("-");
            runningValue *= -1;
        }

        final List<Integer> parts = new LinkedList<>();

        while (runningValue > 0) {
            parts.add(0, (int) (runningValue % 1000));
            runningValue /= 1000;
        }

        for (int index = 0; index < parts.size(); index++) {
            if (index == 0) {
                sb.append(parts.get(index));
            } else {
                final String digits = Integer.toString(parts.get(index));
                ALIGNED_RIGHT.pad(sb, digits, '0', 3, false);
            }

            if (index + 1 < parts.size()) {
                sb.append(",");
            }
        }
    }

    /**
     * Format a number into a comma-separated string.
     *
     * @param value
     * 		the value to format
     * @return the value separated by commas
     */
    public static String commaSeparatedNumber(final long value) {
        final StringBuilder sb = new StringBuilder();
        commaSeparatedNumber(sb, value);
        return sb.toString();
    }

    /**
     * Format a number into a comma-separated string.
     *
     * @param value
     * 		the value to format
     * @param decimalPlaces
     * 		the number of decimal places to show (value is rounded)
     */
    public static void commaSeparatedNumber(final StringBuilder sb, final double value, final int decimalPlaces) {
        if (decimalPlaces < 0) {
            throw new IllegalArgumentException("decimalPlaces must be >= 0");
        }

        final long shift = (long) Math.pow(10, decimalPlaces);
        final double roundedValue = ((double) Math.round(value * shift)) / shift;

        final long wholePart = (long) roundedValue;
        commaSeparatedNumber(sb, wholePart);

        if (decimalPlaces > 0) {
            sb.append(".");
            final double shiftedFraction = Math.abs(value - wholePart) * shift;
            final long roundedFraction = Math.round(shiftedFraction);
            ALIGNED_RIGHT.pad(sb, Long.toString(roundedFraction), '0', decimalPlaces, false);
        }
    }

    /**
     * Format a number into a comma-separated string.
     *
     * @param value
     * 		the value to format
     * @param decimalPlaces
     * 		the number of decimal places to show (value is rounded)
     * @return the value separated by commas
     */
    public static String commaSeparatedNumber(final double value, final int decimalPlaces) {
        final StringBuilder sb = new StringBuilder();
        commaSeparatedNumber(sb, value, decimalPlaces);
        return sb.toString();
    }

    /**
     * Sanitize a timestamp to a string that is save to use in a file name. Replaces ":" with "+".
     *
     * @param timestamp
     * 		the timestamp to sanitize
     * @return the sanitized timestamp
     */
    public static String sanitizeTimestamp(final Instant timestamp) {
        return timestamp.toString().replace(":", "+");
    }

    /**
     * Parse a timestamp from a sanitized string created by {@link #sanitizeTimestamp(Instant)}
     * where ":" was replaced with "+".
     *
     * @param timestamp
     * 		the string to parse
     * @return the parsed timestamp
     */
    public static Instant parseSanitizedTimestamp(final String timestamp) {
        return Instant.parse(timestamp.replace("+", ":"));
    }
}
