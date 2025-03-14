// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.swirlds.platform.gui.hashgraph.HashgraphGui;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.awt.BorderLayout;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;
import javax.swing.JFrame;

/** Helper class to open a {@link HashgraphGui} window */
public final class HashgraphGuiRunner {
    private HashgraphGuiRunner() {}

    /**
     * Open a {@link HashgraphGui} window that draws data from the source provided
     *
     * @param guiSource the source for the GUI
     * @param additionalControls additional controls that will be added to the bottom of the screen
     */
    public static void runHashgraphGui(
            @NonNull final HashgraphGuiSource guiSource, @Nullable final JComponent additionalControls) {
        final JFrame frame = new JFrame();
        final CloseDetector closeDetector = new CloseDetector();
        frame.addWindowListener(closeDetector);
        frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
        frame.setLayout(new BorderLayout());
        frame.setFocusable(true);
        frame.requestFocusInWindow();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        final HashgraphGui hashgraphGui = new HashgraphGui(guiSource);
        hashgraphGui.reloadSource();

        frame.add(hashgraphGui, BorderLayout.NORTH);
        if (additionalControls != null) {
            frame.add(additionalControls, BorderLayout.SOUTH);
        }

        frame.setVisible(true);

        while (!closeDetector.isClosed()) {
            // gets new events
            hashgraphGui.reloadSource();
            hashgraphGui.repaint();
            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private static class CloseDetector implements WindowListener {
        private final AtomicBoolean isClosed = new AtomicBoolean(false);

        public boolean isClosed() {
            return isClosed.get();
        }

        @Override
        public void windowOpened(final WindowEvent e) {
            // no-op
        }

        @Override
        public void windowClosing(final WindowEvent e) {
            isClosed.set(true);
        }

        @Override
        public void windowClosed(final WindowEvent e) {
            isClosed.set(true);
        }

        @Override
        public void windowIconified(final WindowEvent e) {
            // no-op
        }

        @Override
        public void windowDeiconified(final WindowEvent e) {
            // no-op
        }

        @Override
        public void windowActivated(final WindowEvent e) {
            // no-op
        }

        @Override
        public void windowDeactivated(final WindowEvent e) {
            // no-op
        }
    }
}
