// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.utility;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;

/**
 * Utility class for other operations
 */
public class CommonUtils {

    private CommonUtils() {}

    /** the default charset used by swirlds */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    /** lower characters for hex conversion */
    private static final char[] DIGITS_LOWER = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /**
     * Normalizes the string in accordance with the Swirlds default normalization method (NFD) and returns the bytes of
     * that normalized String encoded in the Swirlds default charset (UTF8). This is important for having a consistent
     * method of converting Strings to bytes that will guarantee that two identical strings will have an identical byte
     * representation
     *
     * @param s the String to be converted to bytes
     * @return a byte representation of the String
     */
    @Nullable
    public static byte[] getNormalisedStringBytes(final String s) {
        if (s == null) {
            return null;
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).getBytes(CommonUtils.DEFAULT_CHARSET);
    }

    /**
     * Reverse of {@link #getNormalisedStringBytes(String)}
     *
     * @param bytes the bytes to convert
     * @return a String created from the input bytes
     */
    @NonNull
    public static String getNormalisedStringFromBytes(final byte[] bytes) {
        return new String(bytes, CommonUtils.DEFAULT_CHARSET);
    }

    /**
     * Converts an array of bytes to a lowercase hexadecimal string.
     *
     * @param bytes  the array of bytes to hexadecimal
     * @param length the length of the array to convert to hex
     * @return a {@link String} containing the lowercase hexadecimal representation of the byte array
     */
    @NonNull
    public static String hex(@Nullable final byte[] bytes, final int length) {
        if (bytes == null) {
            return "null";
        }
        throwRangeInvalid("length", length, 0, bytes.length);

        final char[] out = new char[length << 1];
        for (int i = 0, j = 0; i < length; i++) {
            out[j++] = DIGITS_LOWER[(0xF0 & bytes[i]) >>> 4];
            out[j++] = DIGITS_LOWER[0x0F & bytes[i]];
        }

        return new String(out);
    }

    /**
     * Converts Bytes to a lowercase hexadecimal string.
     *
     * @param bytes  the bytes to hexadecimal
     * @param length the length of the array to convert to hex
     * @return a {@link String} containing the lowercase hexadecimal representation of the byte array
     */
    @NonNull
    public static String hex(@Nullable final Bytes bytes, final int length) {
        if (bytes == null) {
            return "null";
        }
        throwRangeInvalid("length", length, 0, (int) bytes.length());

        final char[] out = new char[length << 1];
        for (int i = 0, j = 0; i < length; i++) {
            out[j++] = DIGITS_LOWER[(0xF0 & bytes.getByte(i)) >>> 4];
            out[j++] = DIGITS_LOWER[0x0F & bytes.getByte(i)];
        }

        return new String(out);
    }

    /**
     * Equivalent to calling {@link #hex(Bytes, int)} with length set to {@link Bytes#length()}
     *
     * @param bytes an array of bytes
     * @return a {@link String} containing the lowercase hexadecimal representation of the byte array
     */
    @NonNull
    public static String hex(@Nullable final Bytes bytes) {
        return hex(bytes, bytes == null ? 0 : Math.toIntExact(bytes.length()));
    }

    /**
     * Equivalent to calling {@link #hex(byte[], int)} with length set to bytes.length
     *
     * @param bytes an array of bytes
     * @return a {@link String} containing the lowercase hexadecimal representation of the byte array
     */
    @NonNull
    public static String hex(@Nullable final byte[] bytes) {
        return hex(bytes, bytes == null ? 0 : bytes.length);
    }

    /**
     * Converts a hexadecimal string back to the original array of bytes.
     *
     * @param string the hexadecimal string to be converted
     * @return an array of bytes
     */
    @Nullable
    public static byte[] unhex(@Nullable final String string) {
        if (string == null) {
            return null;
        }

        final char[] data = string.toCharArray();
        final int len = data.length;

        if ((len & 0x01) != 0) {
            throw new IllegalArgumentException("Odd number of characters.");
        }

        final byte[] out = new byte[len >> 1];

        for (int i = 0, j = 0; j < len; i++) {
            int f = toDigit(data[j], j) << 4;
            j++;
            f = f | toDigit(data[j], j);
            j++;
            out[i] = (byte) (f & 0xFF);
        }

        return out;
    }

    /**
     * Converts an {@link Instant} to a {@link Timestamp}
     *
     * @param instant the {@code Instant} to convert
     * @return the {@code Timestamp} equivalent of the {@code Instant}
     */
    @Nullable
    public static Timestamp toPbjTimestamp(@Nullable final Instant instant) {
        if (instant == null) {
            return null;
        }
        return new Timestamp(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * Converts a {@link Timestamp} to an {@link Instant}
     *
     * @param timestamp the {@code Timestamp} to convert
     * @return the {@code Instant} equivalent of the {@code Timestamp}
     */
    @Nullable
    public static Instant fromPbjTimestamp(@Nullable final Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    private static int toDigit(final char ch, final int index) throws IllegalArgumentException {
        final int digit = Character.digit(ch, 16);
        if (digit == -1) {
            throw new IllegalArgumentException("Illegal hexadecimal character " + ch + " at index " + index);
        }
        return digit;
    }

    private static void throwRangeInvalid(final String name, final int value, final int minValue, final int maxValue) {
        if (value < minValue || value > maxValue) {
            throw new IllegalArgumentException(String.format(
                    "The argument '%s' should have a value between %d and %d! Value provided is %d",
                    name, minValue, maxValue, value));
        }
    }
}
