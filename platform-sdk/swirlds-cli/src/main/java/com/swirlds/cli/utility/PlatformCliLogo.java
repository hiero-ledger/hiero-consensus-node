// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_WHITE;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;
import static com.swirlds.common.formatting.TextEffect.RED;
import static com.swirlds.common.formatting.TextEffect.YELLOW;

import com.swirlds.common.formatting.TextEffect;

/**
 * ASCII art logo for the platform CLI.
 */
public final class PlatformCliLogo {

    private PlatformCliLogo() {}

    /**
     * PCLI logo without ANSI colors.
     */
    public static final String LOGO =
            """
			 .----------------.  .----------------.  .----------------.  .----------------.
			| .--------------. || .--------------. || .--------------. || .--------------. |
			| |   ______     | || |     ______   | || |   _____      | || |     _____    | |
			| |  |_   __ \\   | || |   .' ___  |  | || |  |_   _|     | || |    |_   _|   | |
			| |    | |__) |  | || |  / .'   \\_|  | || |    | |       | || |      | |     | |
			| |    |  ___/   | || |  | |         | || |    | |   _   | || |      | |     | |
			| |   _| |_      | || |  \\ `.___.'\\  | || |   _| |__/ |  | || |     _| |_    | |
			| |  |_____|     | || |   `._____.'  | || |  |________|  | || |    |_____|   | |
			| |              | || |              | || |              | || |              | |
			| '--------------' || '--------------' || '--------------' || '--------------' |
			 '----------------'  '----------------'  '----------------'  '----------------'
			""";

    /**
     * Build a colorized version of the logo. If colors are disabled, the logo will be returned without color.
     */
    public static String getColorizedLogo() {
        if (!TextEffect.areTextEffectsEnabled()) {
            return LOGO;
        }

        final StringBuilder sb = new StringBuilder();

        final TextEffect outerBoxColor = BRIGHT_WHITE;
        final TextEffect pInnerBoxColor = RED;
        final TextEffect innerBoxColor = YELLOW;
        final TextEffect pColor = BRIGHT_RED;
        final TextEffect letterColor = BRIGHT_YELLOW;

        final String[] lines = LOGO.split("\n");

        // each of the four boxes has an equal number of characters.
        // Get the total length from the second line since the first line is shorter.
        final int boxWidth = lines[1].length() / 4;

        // The vertical edges of each box have this length
        final int boxEdgeWidth = 3;

        // The remaining characters in each box are for the letters.
        final int letterWidth = boxWidth - boxEdgeWidth * 2;

        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            final String line = lines[lineNumber];

            if (lineNumber == 0 || lineNumber == lines.length - 1) {
                // The first and last two lines are outer borders
                outerBoxColor.apply(sb, line);
            } else if (lineNumber == 1 || lineNumber == lines.length - 2) {
                // the second and second to last lines have no letter characters

                int start;
                int end = 0;

                for (int letterIndex = 0; letterIndex < 4; letterIndex++) {
                    start = end;
                    end += 1;
                    outerBoxColor.apply(sb, line.substring(start, end));

                    start = end;
                    end += (boxWidth - 2);
                    if (letterIndex == 0) {
                        pInnerBoxColor.apply(sb, line.substring(start, end));
                    } else {
                        innerBoxColor.apply(sb, line.substring(start, end));
                    }

                    start = end;
                    end += 1;
                    outerBoxColor.apply(sb, line.substring(start, end));
                }

            } else {
                // The remaining lines have letter characters.

                int start;
                int end = 0;

                for (int letterIndex = 0; letterIndex < 4; letterIndex++) {
                    start = end;
                    end += 1;
                    outerBoxColor.apply(sb, line.substring(start, end));

                    start = end;
                    end += 2;
                    if (letterIndex == 0) {
                        pInnerBoxColor.apply(sb, line.substring(start, end));
                    } else {
                        innerBoxColor.apply(sb, line.substring(start, end));
                    }

                    start = end;
                    end += letterWidth;
                    if (letterIndex == 0) {
                        pColor.apply(sb, line.substring(start, end));
                    } else {
                        letterColor.apply(sb, line.substring(start, end));
                    }

                    start = end;
                    end += 2;
                    if (letterIndex == 0) {
                        pInnerBoxColor.apply(sb, line.substring(start, end));
                    } else {
                        innerBoxColor.apply(sb, line.substring(start, end));
                    }

                    start = end;
                    end += 1;
                    outerBoxColor.apply(sb, line.substring(start, end));
                }
            }

            if (lineNumber + 1 < lines.length) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
