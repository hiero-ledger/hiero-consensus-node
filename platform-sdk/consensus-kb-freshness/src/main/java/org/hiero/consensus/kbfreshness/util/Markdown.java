// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.util;

import java.util.Locale;

/**
 * Stateless helpers for reading and rendering the markdown the checker parses: detecting a fenced
 * code-block delimiter (so example or commented-out code is never read as a claim) and humanizing an
 * enum constant for display.
 */
public final class Markdown {

    /** Prevents instantiation of this utility class. */
    private Markdown() {}

    /**
     * Whether a line opens or closes a fenced code block: after trimming, it starts with a triple
     * backtick or triple tilde. Callers toggle their own in-fence state when this returns {@code true}.
     *
     * @param line the raw line.
     * @return {@code true} when the line is a code-fence delimiter.
     */
    public static boolean isFenceDelimiter(final String line) {
        final String stripped = line.strip();
        return stripped.startsWith("```") || stripped.startsWith("~~~");
    }

    /**
     * Renders an enum constant for display: its name lowercased (locale-independently) with underscores
     * turned into spaces (e.g. {@code SOURCE_PATH} to {@code source path}).
     *
     * @param value the enum constant.
     * @return the humanized name.
     */
    public static String humanize(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
