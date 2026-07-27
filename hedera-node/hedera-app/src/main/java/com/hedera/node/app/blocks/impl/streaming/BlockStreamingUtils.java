package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.Nullable;

public final class BlockStreamingUtils {

    static final long KB_TO_BYTES = 1024;
    static final long MB_TO_BYTES = KB_TO_BYTES * 1024;
    static final long GB_TO_BYTES = MB_TO_BYTES * 1024;

    private BlockStreamingUtils() {
        throw new UnsupportedOperationException();
    }

    /**
     * Converts a size string into a byte value. Accepted units are gigabyte (G|g), megabyte (M|m), kilobyte (K|k), or
     * unspecified which is interpreted as simple bytes.
     *
     * @param string the string to parse
     * @return the input string as bytes, or -1 if parsing failed for any reason including negative values
     */
    public static long parseToBytes(@Nullable final String string) {
        if (string == null || string.isBlank()) {
            return -1;
        }

        final String str = string.trim();
        final char unit = str.charAt(str.length() - 1);
        final long bytes;

        if ('G' == unit || 'g' == unit) {
            // parse gigabytes
            final long parsedGigabytes = parseLong(str.substring(0, str.length() - 2));
            final long parsedBytes = multiply(GB_TO_BYTES, parsedGigabytes);
            bytes = Math.max(-1L, parsedBytes);
        } else if ('M' == unit || 'm' == unit) {
            // parse megabytes
            final long parsedMegabytes = parseLong(str.substring(0, str.length() - 2));
            final long parsedBytes = multiply(MB_TO_BYTES, parsedMegabytes);
            bytes = Math.max(-1L, parsedBytes);
        } else if ('K' == unit || 'k' == unit) {
            // parse kilobytes
            final long parsedKilobytes = parseLong(str.substring(0, str.length() - 2));
            final long parsedBytes = multiply(KB_TO_BYTES, parsedKilobytes);
            bytes = Math.max(-1L, parsedBytes);
        } else if (Character.isDigit(unit)) {
            // parse bytes
            final long parsedBytes = parseLong(str);
            bytes = Math.max(-1L, parsedBytes);
        } else {
            bytes = -1;
        }

        return bytes;
    }

    private static long multiply(final long a, final long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (final ArithmeticException _) {
            return -1;
        }
    }

    private static long parseLong(final String string) {
        try {
            return Long.parseLong(string);
        } catch (final NumberFormatException _) {
            return -1;
        }
    }
}
