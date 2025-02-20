// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import java.util.List;

/**
 * A utility for ANSI Text effects (e.g. color, underlines). <img src="TextEffect-Colors.png" alt="TextEffect">
 */
public enum TextEffect {
    RESET(0),
    BOLD(1),
    ITALIC(3),
    UNDERLINE(4),
    CROSSED_OUT(9),
    RED(31),
    GREEN(32),
    YELLOW(33),
    BLUE(34),
    PURPLE(35),
    CYAN(36),
    WHITE(37),
    GRAY(90),
    BRIGHT_RED(91),
    BRIGHT_GREEN(92),
    BRIGHT_YELLOW(93),
    BRIGHT_BLUE(94),
    BRIGHT_PURPLE(95),
    BRIGHT_CYAN(96),
    BRIGHT_WHITE(97);

    private static boolean textEffectsEnabled = false;

    /**
     * Set if color should be enabled in this JVM. Colored output (and other ANSI formatted output) should only
     * ever be written to a terminal, not to a file or other output stream. When the JVM starts, if we
     * know we are in an environment that supports color then we should enable this. If we are unsure then
     * we can't enable color. This method should be called before any threads are started, and it is not thread
     * safe to call this method in parallel to threads that use text effects.
     */
    public static void setTextEffectsEnabled(final boolean textEffectsEnabled) {
        TextEffect.textEffectsEnabled = textEffectsEnabled;
    }

    /**
     * Check if color is enabled.
     */
    public static boolean areTextEffectsEnabled() {
        return textEffectsEnabled;
    }

    private static final String FORMAT_ESCAPE = "\u001B[";
    private static final String FORMAT_SEPARATOR = ";";
    private static final String FORMAT_END = "m";

    private final int code;

    TextEffect(final int code) {
        this.code = code;
    }

    /**
     * Get the ANSI code for this effect.
     */
    public int getCode() {
        return code;
    }

    /**
     * Apply this text effect to some text.
     *
     * @param text
     * 		the text
     * @return a string formatted with the effect (if color isn't disabled)
     */
    public String apply(final String text) {
        return applyEffects(text, this);
    }

    /**
     * Apply this text effect to some text and write the result to a string builder.
     *
     * @param sb
     * 		a string builder to write to
     * @param text
     * 		the text
     */
    public void apply(final StringBuilder sb, final String text) {
        applyEffects(sb, text, this);
    }

    /**
     * Generate the ANSI escape sequence for zero or more text effects.
     */
    private static void generateEscapeSequence(final StringBuilder sb, TextEffect... effects) {
        if (effects == null || effects.length == 0) {
            return;
        }

        sb.append(FORMAT_ESCAPE);
        for (int index = 0; index < effects.length; index++) {
            sb.append(effects[index].getCode());
            if (index < effects.length - 1) {
                sb.append(FORMAT_SEPARATOR);
            }
        }
        sb.append(FORMAT_END);
    }

    /**
     * Generate the ANSI escape sequence for zero or more text effects.
     */
    private static void generateEscapeSequence(final StringBuilder sb, List<TextEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return;
        }

        sb.append(FORMAT_ESCAPE);
        for (int index = 0; index < effects.size(); index++) {
            sb.append(effects.get(index).getCode());
            if (index < effects.size() - 1) {
                sb.append(FORMAT_SEPARATOR);
            }
        }
        sb.append(FORMAT_END);
    }

    /**
     * Apply zero or more text effects to some text.
     *
     * @param text
     * 		the text
     * @param effects
     * 		zero or more effects
     * @return a string formatted with the effect
     */
    public static String applyEffects(final String text, final TextEffect... effects) {
        if (!textEffectsEnabled) {
            return text;
        }

        final StringBuilder sb = new StringBuilder();
        applyEffects(sb, text, effects);
        return sb.toString();
    }

    /**
     * Apply zero or more text effects to some text and write the result to a string builder.
     *
     * @param sb
     * 		the text is added to this string builder
     * @param text
     * 		the text
     * @param effects
     * 		zero or more effects
     */
    public static void applyEffects(final StringBuilder sb, final String text, final TextEffect... effects) {
        if (!textEffectsEnabled) {
            sb.append(text);
            return;
        }

        generateEscapeSequence(sb, effects);
        sb.append(text);
        generateEscapeSequence(sb, RESET);
    }

    /**
     * Apply zero or more text effects to some text.
     *
     * @param text
     * 		the text
     * @param effects
     * 		zero or more effects
     * @return a string formatted with the effect
     */
    public static String applyEffects(final String text, final List<TextEffect> effects) {
        if (!textEffectsEnabled) {
            return text;
        }

        final StringBuilder sb = new StringBuilder();
        applyEffects(sb, text, effects);
        return sb.toString();
    }

    /**
     * Apply zero or more text effects to some text and write the result to a string builder.
     *
     * @param sb
     * 		the text is added to this string builder
     * @param text
     * 		the text
     * @param effects
     * 		zero or more effects
     */
    public static void applyEffects(final StringBuilder sb, final String text, final List<TextEffect> effects) {
        if (!textEffectsEnabled) {
            sb.append(text);
            return;
        }

        generateEscapeSequence(sb, effects);
        sb.append(text);
        generateEscapeSequence(sb, RESET);
    }

    /**
     * Parse a string and figure out the number of printable characters in that string
     * (i.e. the length minus the ANSI escape codes).
     *
     * @param formattedString
     * 		a string that may contain ANSI escape codes
     * @return the number of printable characters in the string
     */
    public static int getPrintableTextLength(final String formattedString) {
        int length = 0;
        for (int i = 0; i < formattedString.length(); i++) {
            if (formattedString.charAt(i) == '\u001B') {
                while (formattedString.charAt(i) != 'm') {
                    i++;
                }
            } else {
                length++;
            }
        }
        return length;
    }
}
