// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static com.swirlds.common.units.DataUnit.UNIT_BYTES;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Utility class for other operations
 */
public class CommonUtils {

    /** the default charset used by swirlds */
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    /** lower characters for hex conversion */
    private static final char[] DIGITS_LOWER = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /** used by beep() */
    private static Synthesizer synthesizer;

    /** used by click(). It is opened and never closed. */
    private static Clip clip = null;

    /** used by click() */
    private static byte[] data = null;

    /** used by click() */
    private static AudioFormat format = null;

    /**
     * Normalizes the string in accordance with the Swirlds default normalization method (NFD) and returns the bytes of
     * that normalized String encoded in the Swirlds default charset (UTF8). This is important for having a consistent
     * method of converting Strings to bytes that will guarantee that two identical strings will have an identical byte
     * representation
     *
     * @param s the String to be converted to bytes
     * @return a byte representation of the String
     */
    public static byte[] getNormalisedStringBytes(final String s) {
        if (s == null) {
            return null;
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).getBytes(DEFAULT_CHARSET);
    }

    /**
     * Reverse of {@link #getNormalisedStringBytes(String)}
     *
     * @param bytes the bytes to convert
     * @return a String created from the input bytes
     */
    public static String getNormalisedStringFromBytes(final byte[] bytes) {
        return new String(bytes, DEFAULT_CHARSET);
    }

    /**
     * Play a beep sound. It is middle C, half volume, 20 milliseconds.
     */
    public static void beep() {
        beep(60, 64, 20);
    }

    /**
     * Make a beep sound.
     *
     * @param pitch    the pitch, from 0 to 127, where 60 is middle C, 61 is C#, etc.
     * @param velocity the "velocity" (volume, or speed with which the note is played). 0 is silent, 127 is max.
     * @param duration the number of milliseconds the sound will play
     */
    public static void beep(final int pitch, final int velocity, final int duration) {
        try {
            if (synthesizer == null) {
                synthesizer = MidiSystem.getSynthesizer();
                synthesizer.open();
            }

            final MidiChannel[] channels = synthesizer.getChannels();

            channels[0].noteOn(pitch, velocity);
            Thread.sleep(duration);
            channels[0].noteOff(60);
        } catch (final Exception e) {
        }
    }

    /**
     * Make a click sound.
     */
    public static void click() {
        try {
            if (data == null) {
                data = new byte[] {0, 127};
                format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100.0f, 16, 1, 2, 44100.0f, false);
                clip = AudioSystem.getClip();
                clip.open(format, data, 0, data.length);
            }
            clip.start(); // play the waveform in data
            while (clip.getFramePosition() < clip.getFrameLength()) {
                Thread.yield(); // busy wait, but it's only for a short time, and at least it yields
            }
            clip.stop(); // it should have already stopped
            clip.setFramePosition(0); // for next time, start over
        } catch (final Exception e) {
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
     * This is equivalent to sending text to doing both Utilities.tellUserConsole() and writing to a popup window. It is
     * not used for debugging; it is used for production code for communicating to the user.
     *
     * @param title the title of the window to pop up
     * @param msg   the message for the user
     */
    public static void tellUserConsolePopup(final String title, final String msg) {
        tellUserConsole("\n***** " + msg + " *****\n");
        if (!GraphicsEnvironment.isHeadless()) {
            final String[] ss = msg.split("\n");
            int w = 0;
            for (final String str : ss) {
                w = Math.max(w, str.length());
            }
            final JTextArea ta = new JTextArea(ss.length + 1, (int) (w * 0.65));
            ta.setText(msg);
            ta.setWrapStyleWord(true);
            ta.setLineWrap(true);
            ta.setCaretPosition(0);
            ta.setEditable(false);
            ta.addHierarchyListener(
                    new HierarchyListener() { // make ta resizable
                        @Override
                        public void hierarchyChanged(final HierarchyEvent e) {
                            final Window window = SwingUtilities.getWindowAncestor(ta);
                            if (window instanceof Dialog) {
                                final Dialog dialog = (Dialog) window;
                                if (!dialog.isResizable()) {
                                    dialog.setResizable(true);
                                }
                            }
                        }
                    });
            final JScrollPane sp = new JScrollPane(ta);
            JOptionPane.showMessageDialog(null, sp, title, JOptionPane.PLAIN_MESSAGE);
        }
    }

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

    /**
     * Convert an int to a byte array, little endian.
     *
     * @param value the int to convert
     * @return the byte array
     */
    public static byte[] intToBytes(final int value) {
        final byte[] dst = new byte[Integer.BYTES];

        for (int i = 0; i < Integer.BYTES; i++) {
            final int shift = i * 8;
            dst[i] = (byte) (0xff & (value >> shift));
        }
        return dst;
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

    /**
     * Joins multiple lists into a single list
     *
     * @param lists the lists to join
     * @param <T>   the type of element in the list
     * @return the list containing all elements in the supplied lists
     */
    @SafeVarargs
    public static <T> List<T> joinLists(final List<T>... lists) {
        return Arrays.stream(lists).flatMap(Collection::stream).toList();
    }

    /**
     * Converts a {@code null} string reference to an empty string.
     *
     * @param value a possibly {@code null} string reference.
     * @return the original value if not null or an empty string if null.
     */
    public static String nullToBlank(final String value) {
        return (value == null) ? "" : value;
    }

    /**
     * Combine an array of consumers into a single consumer that calls all of them
     *
     * @param consumers the consumers to combine
     * @param <T>       the type being consumed
     * @return the combined consumer
     */
    @SafeVarargs
    public static <T> Consumer<T> combineConsumers(final Consumer<T>... consumers) {
        return t -> {
            for (final Consumer<T> consumer : consumers) {
                consumer.accept(t);
            }
        };
    }

    /**
     * Same as {@link #combineConsumers(Consumer[])} but with a list instead of an array
     */
    public static <T> Consumer<T> combineConsumers(final List<Consumer<T>> consumers) {
        return t -> {
            for (final Consumer<T> consumer : consumers) {
                consumer.accept(t);
            }
        };
    }

    /**
     * Returns a string representation of the given byte count in human readable format.
     *
     * @param bytes number of bytes
     * @return human-readable string representation of the given byte count
     */
    public static String byteCountToDisplaySize(final long bytes) {
        return UNIT_BYTES.buildFormatter(bytes).setDecimalPlaces(1).render();
    }
}
