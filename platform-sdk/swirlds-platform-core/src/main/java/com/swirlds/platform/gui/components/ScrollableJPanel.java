// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.components;

import javax.swing.JScrollPane;

/**
 * A JScrollPane containing the given UpdatableJPanel.
 *
 * @author Leemon
 */
public class ScrollableJPanel extends JScrollPane {
    private static final long serialVersionUID = 1L;
    private final PrePaintableJPanel contents;

    /**
     * Wrap the given panel in scroll bars, and remember it so that calls to prePaint() can be passed on to it.
     */
    public ScrollableJPanel(PrePaintableJPanel contents) {
        super(contents);
        this.contents = contents;
        contents.setVisible(true);
    }

    /**
     * Recalculate the contents of each Component, maybe slowly, so that the next repaint() will trigger a fast render
     * of everything.
     */
    public void prePaint() {
        if (contents != null) {
            contents.prePaint();
        }
    }

    public PrePaintableJPanel getContents() {
        return contents;
    }
}
