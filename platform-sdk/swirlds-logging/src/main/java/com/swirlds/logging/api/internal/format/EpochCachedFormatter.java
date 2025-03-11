// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.format;

import static com.swirlds.logging.utils.StringUtils.toPaddedDigitsString;
import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static java.time.temporal.ChronoField.YEAR;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * An epoc millis parser to human-readable String based on pattern: {@code "yyyy-MM-dd HH:mm:ss.SSS"}
 */
public class EpochCachedFormatter {

    /**
     * The formatter for the timestamp.
     */
    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
            .appendValue(YEAR, 4, 4, SignStyle.EXCEEDS_PAD)
            .appendLiteral('-')
            .appendValue(MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(DAY_OF_MONTH, 2)
            .appendLiteral(' ')
            .appendValue(HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(NANO_OF_SECOND, 3, 3, true)
            .toFormatter()
            .withZone(UTC);

    private final Map<Instant, String> exactCache = new ShrinkableSizeCache<>(1);
    private final Map<Instant, String> dateCache = new ShrinkableSizeCache<>(10);
    private static final String[] TWO_SPACE_DIGITS_CACHE =
            IntStream.range(0, 60).mapToObj(i -> toPaddedDigitsString(i, 2)).toArray(String[]::new);
    private static final String[] THREE_SPACE_DIGITS_CACHE =
            IntStream.range(0, 1000).mapToObj(i -> toPaddedDigitsString(i, 3)).toArray(String[]::new);

    /**
     * Creates a parser and preloads the caches with {@link System#currentTimeMillis()}
     */
    public EpochCachedFormatter() {
        // precompute values for now
        format(System.currentTimeMillis());
    }

    /**
     * Parses the {@code epochMillis} into a String. It uses caches to speed up future so subsequents calls within the
     * day/hour/millisecond are faster. For non cached times it introduces a time-penalization compared to
     * {@link DateTimeFormatter#format(TemporalAccessor)} for updating caches. To minimize this effect at instantiation,
     * it preloads the information for the current time.
     *
     * @param epochMillis epoch millis to convert such as those obtained form {@link System#currentTimeMillis()}
     * @return the human-readable representation of the string based on pattern: {@code "yyyy-MM-dd HH:mm:ss.SSS"}
     */
    public @NonNull String format(final long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        String stringDate = exactCache.get(instant);
        if (stringDate == null) {
            stringDate = getFromDate(instant);
        }
        if (stringDate == null) {
            stringDate = getFromFormatter(instant);
        }
        return stringDate;
    }

    /**
     * Creates a String representation of the instant using {@code FORMATTER}.
     */
    @NonNull
    private String getFromFormatter(final @NonNull Instant instant) {
        String stringDate = FORMATTER.format(instant);
        exactCache.put(instant, stringDate);
        dateCache.put(instant.truncatedTo(ChronoUnit.DAYS), stringDate.substring(0, 11));
        return stringDate;
    }

    /**
     * Tries to create a String representation of the instant using previously cached info in {@code dateCache}.
     * Returns null if not information for the day is cached.
     */
    private @Nullable String getFromDate(final @NonNull Instant instant) {
        final String format = dateCache.get(instant.truncatedTo(ChronoUnit.DAYS));

        if (format == null) {
            return null;
        }

        final StringBuilder buffer = new StringBuilder(format);
        long totalSeconds = instant.getEpochSecond();
        final int hour = (int) ((totalSeconds / 3600) % 24);
        buffer.append(TWO_SPACE_DIGITS_CACHE[hour]);
        buffer.append(":");
        final int minute = (int) ((totalSeconds / 60) % 60);
        buffer.append(TWO_SPACE_DIGITS_CACHE[minute]);
        buffer.append(":");
        final int second = (int) (totalSeconds % 60);
        buffer.append(TWO_SPACE_DIGITS_CACHE[second]);
        buffer.append(".");
        final int milliseconds = instant.getNano() / 1_000_000;
        buffer.append(THREE_SPACE_DIGITS_CACHE[milliseconds]);
        final String stringDate = buffer.toString();
        exactCache.put(instant, stringDate);
        return stringDate;
    }
}
