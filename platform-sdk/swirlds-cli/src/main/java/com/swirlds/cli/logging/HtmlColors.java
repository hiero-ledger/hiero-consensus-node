// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import com.swirlds.common.formatting.TextEffect;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;

/**
 * Maps ANSI colors to HTML compatible colors
 */
public class HtmlColors {
    /**
     * Hidden constructor
     */
    private HtmlColors() {}

    /**
     * The map of ANSI colors to HTML compatible colors. The colors are experimentally determined with a color picker
     */
    public static final Map<TextEffect, String> ansiToHtmlColors = Map.of(
            TextEffect.WHITE, "#808181",
            TextEffect.GRAY, "#595858",
            TextEffect.BRIGHT_RED, "#f13f4c",
            TextEffect.BRIGHT_GREEN, "#4db815",
            TextEffect.BRIGHT_YELLOW, "#e5be01",
            TextEffect.BRIGHT_BLUE, "#1ea6ee",
            TextEffect.BRIGHT_PURPLE, "#ed7fec",
            TextEffect.BRIGHT_CYAN, "#00e5e5",
            TextEffect.BRIGHT_WHITE, "#fdfcfc");

    /**
     * Get the HTML compatible color for the given ANSI color
     *
     * @param ansiColor the ANSI color
     * @return the HTML compatible color
     */
    @NonNull
    public static String getHtmlColor(@NonNull final TextEffect ansiColor) {
        Objects.requireNonNull(ansiColor);

        if (!ansiToHtmlColors.containsKey(ansiColor)) {
            throw new IllegalArgumentException("The given ANSI color is not supported: " + ansiColor);
        }

        return ansiToHtmlColors.get(ansiColor);
    }
}
