// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.internal;

import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Utility class for other operations
 */
public class CommonUtils {

    /** lower characters for hex conversion */
    private static final char[] DIGITS_LOWER = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /**
     * Converts an array of bytes to a lowercase hexadecimal string.
     *
     * @param bytes  the array of bytes to hexadecimal
     * @param length the length of the array to convert to hex
     * @return a {@link String} containing the lowercase hexadecimal representation of the byte array
     */
    @SuppressWarnings("java:S127")
    public static String hex(final byte[] bytes, final int length) {
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
    public static String hex(final Bytes bytes, final int length) {
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

    public static String hex(final Bytes bytes) {
        return hex(bytes, bytes == null ? 0 : Math.toIntExact(bytes.length()));
    }

    /**
     * Equivalent to calling {@link #hex(byte[], int)} with length set to bytes.length
     *
     * @param bytes an array of bytes
     * @return a {@link String} containing the lowercase hexadecimal representation of the byte array
     */
    public static String hex(final byte[] bytes) {
        return hex(bytes, bytes == null ? 0 : bytes.length);
    }

    /**
     * Converts a hexadecimal string back to the original array of bytes.
     *
     * @param string the hexadecimal string to be converted
     * @return an array of bytes
     */
    @SuppressWarnings("java:S127")
    public static byte[] unhex(final String string) {
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

    private static int toDigit(final char ch, final int index) throws IllegalArgumentException {
        final int digit = Character.digit(ch, 16);
        if (digit == -1) {
            throw new IllegalArgumentException("Illegal hexadecimal character " + ch + " at index " + index);
        }
        return digit;
    }

    /**
     * Throws an exception if the value is outside of the specified range
     *
     * @param name     the name of the variable
     * @param value    the value to check
     * @param minValue the minimum allowed value
     * @param maxValue the maximum allowed value
     */
    public static void throwRangeInvalid(final String name, final int value, final int minValue, final int maxValue) {
        if (value < minValue || value > maxValue) {
            throw new IllegalArgumentException(String.format(
                    "The argument '%s' should have a value between %d and %d! Value provided is %d",
                    name, minValue, maxValue, value));
        }
    }
}
