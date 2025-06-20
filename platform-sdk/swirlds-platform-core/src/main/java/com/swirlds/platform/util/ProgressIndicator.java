// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static com.swirlds.common.formatting.TextEffect.BLUE;
import static com.swirlds.common.formatting.TextEffect.CYAN;
import static com.swirlds.common.formatting.TextEffect.GRAY;
import static com.swirlds.common.formatting.TextEffect.GREEN;
import static com.swirlds.common.formatting.TextEffect.PURPLE;
import static com.swirlds.common.formatting.TextEffect.RED;
import static com.swirlds.common.formatting.TextEffect.WHITE;
import static com.swirlds.common.formatting.TextEffect.YELLOW;

import com.swirlds.common.formatting.TextEffect;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A tool used to convey progress to the user.
 */
public class ProgressIndicator {

    private static final String[] LOADING_CHARACTERS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final int CHARACTERS_PER_LINE = LOADING_CHARACTERS.length * 10;
    private static TextEffect[] COLORS = {WHITE, YELLOW, RED, PURPLE, GREEN, CYAN, BLUE, GRAY};

    private int count = 0;
    private int lineNumber = 0;
    private boolean colorEnabled = true;
    private final int threshold;

    public ProgressIndicator(final int threshold) {
        this.threshold = threshold;
    }

    /**
     * Enable or disable color.
     *
     * @param colorEnabled true to enable color, false to disable
     */
    public synchronized void setColorEnabled(final boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    @NonNull
    private String generateCurrentLine(final int lineNumber, final int charactersInLine) {
        final StringBuilder sb = new StringBuilder();
        for (int index = 0; index < charactersInLine; index++) {
            final int characterIndex = index % LOADING_CHARACTERS.length;
            final int colorIndex = (index + lineNumber) % COLORS.length;
            final String nextCharacter;
            if (colorEnabled) {
                nextCharacter = COLORS[colorIndex].apply(LOADING_CHARACTERS[characterIndex]);
            } else {
                nextCharacter = LOADING_CHARACTERS[characterIndex];
            }
            sb.append(nextCharacter);
        }

        return sb.toString();
    }

    /**
     * Increment the progress indicator.
     */
    public synchronized void increment() {
        final int previousProgress = count / threshold;
        count++;
        final int currentProgress = count / threshold;

        if (previousProgress == currentProgress) {
            // We haven't passed a threshold yet
            return;
        }

        final int charactersOnCurrentLine = currentProgress % CHARACTERS_PER_LINE;
        final boolean endOfLine = charactersOnCurrentLine % CHARACTERS_PER_LINE == 0;

        final String line =
                (charactersOnCurrentLine > 0 ? "\r" : "") + generateCurrentLine(lineNumber, charactersOnCurrentLine);

        System.out.print(line);
        System.out.flush();
        if (endOfLine) {
            System.out.println();
            lineNumber++;
        }
    }

    /**
     * Write a message to the console. Resets the progress indicator.
     *
     * @param message the message to write
     */
    public synchronized void writeMessage(@NonNull final String message) {
        System.out.println("\n" + message);
        count = 0;
        lineNumber = 0;
    }
}
