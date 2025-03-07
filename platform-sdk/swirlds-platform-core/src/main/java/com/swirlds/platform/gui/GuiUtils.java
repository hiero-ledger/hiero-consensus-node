// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui;

import com.swirlds.common.Console;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import com.swirlds.platform.gui.components.ScrollableJPanel;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;

/**
 * Miscellaneous GUI utility methods.
 */
public final class GuiUtils {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    private GuiUtils() {}

    /**
     * Insert line breaks into the given string so that each line is at most len characters long (not
     * including trailing whitespace and the line break itself). Line breaks are only inserted after a
     * whitespace character and before a non-whitespace character, resulting in some lines possibly being
     * shorter. If a line has no whitespace, then it will insert between two non-whitespace characters to
     * make it exactly len characters long.
     *
     * @param len
     * 		the desired length
     * @param str
     * 		the input string, where a newline is always just \n (never \n\r or \r or \r\n)
     */
    public static String wrap(final int len, final String str) {
        StringBuilder ans = new StringBuilder();
        String[] lines = str.split("\n"); // break into lines
        for (String line : lines) { // we'll add \n to end of every line, then strip off the last
            if (line.length() == 0) {
                ans.append('\n');
            }
            char[] c = line.toCharArray();
            int i = 0;
            while (i < c.length) { // repeatedly add a string starting at i, followed by a \n
                int j = i + len;
                if (j >= c.length) { // grab the rest of the characters
                    j = c.length;
                } else if (Character.isWhitespace(c[j])) { // grab more than len characters
                    while (j < c.length && Character.isWhitespace(c[j])) {
                        j++;
                    }
                } else { // grab len or fewer characters
                    while (j >= i && !Character.isWhitespace(c[j])) {
                        j--;
                    }
                    if (j < i) { // there is no whitespace before len
                        j = i + len;
                    } else { // the last whitespace before len is at j
                        j++;
                    }
                }
                ans.append(c, i, j - i); // append c[i...j-1]
                ans.append('\n');
                i = j; // continue, starting at j
            }
        }
        return ans.substring(0, ans.length() - 1); // remove the last '\n' that was added
    }

    /**
     * Instantiates and returns a JTextArea whose settings are suitable for use inside the browser window's
     * scroll area in a tab.
     */
    public static JTextArea newJTextArea(final String text) {
        JTextArea txt = new JTextArea(0, 0);
        ((DefaultCaret) txt.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        txt.setBackground(Color.WHITE);
        txt.setForeground(Color.BLACK);
        txt.setDisabledTextColor(Color.BLACK);
        txt.setFont(GuiConstants.FONT);
        txt.setEditable(false);
        txt.setEnabled(false);
        txt.setText(text);
        txt.setVisible(true);
        return txt;
    }

    /**
     * return the rectangle of the recommended window size and location for this instance of the Platform. Both consoles
     * and windows are created to fit in this rectangle, by default.
     *
     * @param winCount the window count
     * @param winNum   this is the Nth Platform running on this machine (N=winNum)
     * @return the recommended Rectangle for this Platform's window
     */
    public static Rectangle winRect(final int winCount, final int winNum) {
        // the goal is to arrange windows on the screen so that the leftmost and rightmost windows just
        // touch the edge of the screen with their outermost border. But the rest of the windows overlap
        // with them and with each other such that all of the border of one window (ecept 2 pixels) overlaps
        // the content of its neighbors. This should look fine on any OS where the borders are thin (i.e.,
        // all except Windows 10), and should also look good on Windows 10, by making the invisible borders
        // overlap the adjacent window rather than looking like visible gaps.
        // In addition, extra space is added to either the left or right side of the
        // screen, whichever is likely to have the close button for the Browser window that lies behind the
        // Platform windows.

        final int leftGap = (IS_WINDOWS ? 0 : 25); // extra space at left screen edge
        final int rightGap = (IS_WINDOWS ? 50 : 0); // extra space at right screen edge
        final Rectangle screenSize =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final int contentWidth =
                (screenSize.width - leftGap - rightGap - WindowConfig.getInsets().left - WindowConfig.getInsets().right)
                        / winCount;
        final int x = screenSize.x + leftGap + contentWidth * winNum;
        final int y = screenSize.y;
        final int width = contentWidth + WindowConfig.getInsets().left + WindowConfig.getInsets().right;
        final int height = screenSize.height;
        return new Rectangle(x, y, width, height);
    }

    /**
     * Add this to a window as a listener so that when the window closes, so does the entire program, including the
     * browser and all platforms.
     *
     * @return a listener that responds to the window closing
     */
    public static WindowAdapter stopper() {
        return new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                SystemExitUtils.exitSystem(SystemExitCode.NO_ERROR, "window closed", true);
            }
        };
    }

    /** set the component to have a white background, and wrap it in scroll bars */
    public static ScrollableJPanel makeScrollableJPanel(PrePaintableJPanel comp) {
        comp.setBackground(Color.WHITE);
        ScrollableJPanel scroll = new ScrollableJPanel(comp);
        scroll.setBackground(Color.WHITE);
        scroll.setVisible(true);
        return scroll;
    }

    public static JPanel createPairPanel(final JPanel controls, final JPanel picture) {
        final JPanel pairPanel = new JPanel();
        pairPanel.setLayout(new GridBagLayout());
        pairPanel.setBackground(Color.WHITE);
        pairPanel.setVisible(true);
        final GridBagConstraints c3 = new GridBagConstraints();
        c3.anchor = GridBagConstraints.FIRST_LINE_START; // left align each component in its cell
        c3.gridx = 0;
        c3.gridy = 0;
        c3.gridwidth = GridBagConstraints.RELATIVE;
        c3.gridheight = GridBagConstraints.REMAINDER;
        c3.fill = GridBagConstraints.BOTH;
        c3.weightx = 0; // don't put extra space in the checkbox side
        c3.weighty = 0;
        pairPanel.add(controls, c3);
        c3.gridx = 1;
        c3.gridwidth = GridBagConstraints.REMAINDER;
        c3.gridheight = GridBagConstraints.REMAINDER;
        c3.weightx = 1.0f;
        c3.weighty = 1.0f;
        c3.fill = GridBagConstraints.BOTH;
        pairPanel.add(picture, c3);

        /////////////////// create spacer ///////////////////
        final JPanel spacer = new JPanel();
        spacer.setBackground(Color.YELLOW);
        spacer.setVisible(true);

        picture.setVisible(true);

        return pairPanel;
    }

    public static JFrame createBasicWindow(final String name, final Rectangle winRect, final boolean visible) {
        final JFrame frame = new JFrame(name);

        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setBackground(Color.DARK_GRAY);
        frame.setSize(winRect.width, winRect.height);
        frame.setPreferredSize(new Dimension(winRect.width, winRect.height));
        frame.setLocation(winRect.x, winRect.y);
        frame.setFocusable(true);
        frame.requestFocusInWindow();
        frame.setVisible(visible); // show it
        return frame;
    }

    public static Console createBasicConsole(final String name, final Rectangle winRect, final boolean visible) {
        final Console console = new Console(name, winRect);
        console.getWindow().setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        console.setVisible(visible);
        return console;
    }
}
