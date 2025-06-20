// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.KeyListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import javax.swing.JFrame;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;

/**
 * A console window. It is similar to the one written to by {@link System#out}. The window has the
 * size/location recommended by the browser, and contains scrolling white text on a black background. If
 * {@code cons} is a {@link Console}, then it can be written to with {@code cons.out.print()} and
 * <code>cons.out.println()</code> in the same way as the System <code>print()</code> and
 * <code>println()</code> write to the Java console.
 */
public class Console {

    public static final double MAX_SIZE_PERCENTAGE = 0.75;

    /**
     * Use {@link #out} to write to this console window in the same way that {@link System#out} is used to
     * write to Java's console window.
     */
    public final PrintStream out;

    private final boolean headless;

    /** the window holding the console */
    private final JFrame window;

    /** the heading text at the top */
    private final JTextArea heading;

    /** the main body text below the heading */
    private final JTextArea textArea;

    /** the scroll pane containing textArea and heading */
    private final JScrollPane scrollPane;

    /** max number of characters stored stored. Older text is deleted. */
    private static final int MAX_SIZE = 50 * 1024;

    private static final int DEFAULT_FONT_SIZE = 12; // 14 is good for for windows

    private class ConsoleStream extends ByteArrayOutputStream {
        @Override
        public synchronized void flush() {
            String str = toString();
            reset();
            if (str.equals("")) {
                return;
            }
            str = textArea.getText() + str;
            int n = (int) (MAX_SIZE_PERCENTAGE * MAX_SIZE);
            if (str.length() > n) {
                int i = str.lastIndexOf("\r", str.length() - n);
                i = Math.max(i, str.lastIndexOf("\n", str.length() - n));
                i = Math.max(i, 0);
                i = Math.max(i, str.length() - MAX_SIZE);
                str = str.substring(i);
            }
            textArea.setText(str);

            final JScrollBar bar = scrollPane.getVerticalScrollBar();
            // the position of a scrollbar handle (its value) is at most maxScroll.
            // this could have used textArea.getHeight() instead of bar.getMaximum().
            int maxScroll = bar.getMaximum() - bar.getModel().getExtent();
            // If the user scrolls to the bottom (or near it), then be sticky, and
            // stay at the exact bottom.
            if (maxScroll - bar.getValue() < .1 * bar.getMaximum()) {
                bar.setValue(maxScroll);
            }
        }
    }

    public Console(final String name, final Rectangle winRect) {
        this(name, winRect, DEFAULT_FONT_SIZE, false);
    }

    public Console(final String name, final Rectangle winRect, final int fontSize, final boolean visible) {
        if (GraphicsEnvironment.isHeadless()) {
            window = null;
            heading = null;
            textArea = null;
            scrollPane = null;
            out = null;
            headless = true;
        } else {
            heading = new JTextArea(2, 40);
            heading.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
            heading.setEditable(false);
            heading.setBackground(Color.BLACK);
            heading.setForeground(Color.WHITE);

            textArea = new JTextArea(10, 40);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
            textArea.setEditable(false);
            textArea.setBackground(Color.BLACK);
            textArea.setForeground(Color.WHITE);

            scrollPane = new JScrollPane();
            scrollPane.setViewportView(textArea);
            scrollPane.setColumnHeaderView(heading);
            scrollPane.setBackground(Color.BLACK);

            window = new JFrame(name); // create a new window
            window.setBackground(Color.BLACK);
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            window.setBackground(Color.DARK_GRAY);
            window.add(scrollPane, BorderLayout.CENTER);
            window.setFocusable(true);
            window.requestFocusInWindow();
            window.setBounds(winRect);
            window.setVisible(visible);

            out = new PrintStream(new ConsoleStream(), true);
            headless = false;
        }
    }

    /**
     * put the given text at the top of the console, above the region that scrolls
     *
     * @param headingText
     * 		the text to display at the top
     */
    public void setHeading(final String headingText) {
        if (headless) {
            throw new IllegalStateException("Heading can not be defined in headless mode!");
        }
        heading.setText(headingText);
        heading.revalidate();
    }

    /**
     * Adds the specified key listener to receive key events from this console.
     *
     * @param listener
     * 		the key listener.
     */
    public synchronized void addKeyListener(final KeyListener listener) {
        if (headless) {
            throw new IllegalStateException("KeyListener can not be added in headless mode!");
        }
        window.addKeyListener(listener);
        heading.addKeyListener(listener);
        scrollPane.addKeyListener(listener);
        textArea.addKeyListener(listener);
    }

    /**
     * set if the window holding console is visible
     *
     * @param visible
     * 		whether the window holding console is visible
     */
    public void setVisible(boolean visible) {
        if (headless) {
            throw new IllegalStateException("Visible state can not be changed in headless mode!");
        }
        window.setVisible(visible);
    }

    /**
     * get window holding the console
     *
     * @return window holding console
     */
    public JFrame getWindow() {
        if (headless) {
            throw new IllegalStateException("No window accessible in headless mode!");
        }
        return window;
    }
}
