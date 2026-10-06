// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

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

    /**
     * This is equivalent to System.out.println(), but is not used for debugging; it is used for production code for
     * communicating to the user. Centralizing it here makes it easier to search for debug prints that might have
     * slipped through before a release.
     *
     * @param msg the message for the user
     */
    public static void tellUserConsole(final String msg) {
        System.out.println(msg);
    }

    /**
     * Calls {@link CommonUtils#tellUserConsole(String)} and highlights the message.
     *
     * @param msg the message for the user
     */
    public static void tellUserConsoleHighlighted(final String msg) {
        tellUserConsole("\n***** " + msg + " *****\n");
    }

    /**
     * Given a name from the address book, return the corresponding alias to associate with certificates in the trust
     * store. This is found by lowercasing all the letters, removing accents, and deleting every character other than
     * letters and digits. A "letter" is anything in the Unicode category "letter", which includes most alphabets, as
     * well as ideographs such as Chinese.
     * <p>
     * WARNING: Some versions of Java 8 have a terrible bug where even a single capital letter in an alias will prevent
     * SSL or TLS connections from working (even though those protocols don't use the aliases). Although this ought to
     * work fine with Chinese/Greek/Cyrillic characters, it is safer to stick with only the 26 English letters.
     *
     * @param name a name from the address book
     * @return the corresponding alias
     */
    public static String nameToAlias(final String name) {
        // Convert to lowercase. The ROOT locale should work with most non-english characters. Though there
        // can be surprises. For example, in Turkey, the capital I would convert in a Turkey-specific way to
        // a "lowercase I without a dot". But ROOT would simply convert it to a lowercase I.
        String alias = name.toLowerCase(Locale.ROOT);

        // Now find each character that is a single Unicode codepoint for an accented character, and convert
        // it to an expanded form consisting of the unmodified letter followed
        // by all its modifiers. So if "à" was encoded as U+00E0, it will be converted to U+0061 U++U0300.
        // This is necessary because Unicode normally allows that character to be encoded either way, and
        // they are normally treated as equivalent.
        alias = Normalizer.normalize(alias, Normalizer.Form.NFD);

        // Finally, delete the modifiers. So the expanded "à" (U+0061 U++U0300) will be converted to "a"
        // (U+0061). Also delete all spaces, punctuation, special characters, etc. Leave only digits and
        // unaccented letters. Specifically, leave only the 10 digits 0-9 and the characters that have a
        // Unicode category of "letter". Letters include alphabets (Latin, Cyrillic, etc.)
        // and ideographs (Chinese, etc.).
        alias = alias.replaceAll("[^\\p{L}0-9]", "");
        return alias;
    }
}
