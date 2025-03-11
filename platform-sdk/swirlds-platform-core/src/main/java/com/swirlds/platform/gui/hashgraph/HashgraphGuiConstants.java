// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph;

import java.awt.Color;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class HashgraphGuiConstants {

    public static final int DEFAULT_GENERATIONS_TO_DISPLAY = 25;
    /** outline of labels */
    public static final Color LABEL_OUTLINE = new Color(255, 255, 255);
    /** unknown-fame witness, non-consensus */
    public static final Color LIGHT_RED = new Color(192, 0, 0);
    /** unknown-fame witness, consensus (which can't happen) */
    public static final Color DARK_RED = new Color(128, 0, 0);
    /** famous witness, non-consensus */
    public static final Color LIGHT_GREEN = new Color(0, 192, 0);
    /** famous witness, consensus */
    public static final Color DARK_GREEN = new Color(0, 128, 0);
    /** non-famous witness, non-consensus */
    public static final Color LIGHT_YELLOW = new Color(160, 160, 0);
    /** non-famous witness, consensus */
    public static final Color DARK_YELLOW = new Color(100, 100, 0);
    /** judge, consensus */
    public static final Color LIGHT_BLUE = new Color(0, 0, 192);
    /** judge, non-consensus */
    public static final Color DARK_BLUE = new Color(0, 0, 128);
    /** non-witness, consensus */
    public static final Color LIGHT_GRAY = new Color(160, 160, 160);
    /** non-witness, non-consensus */
    public static final Color DARK_GRAY = new Color(0, 0, 0);

    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("H:m:s.n").withLocale(Locale.US).withZone(ZoneId.systemDefault());
    public static final Font HASHGRAPH_PICTURE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private HashgraphGuiConstants() {}
}
