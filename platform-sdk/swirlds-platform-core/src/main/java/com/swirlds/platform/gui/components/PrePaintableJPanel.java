// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.components;

import java.awt.FlowLayout;
import java.io.Serial;
import javax.swing.JPanel;

/**
 * A JPanel with an additional method prePaint(), which (possibly slowly) recalculates everything, so
 * the next repaint() will (quickly) render everything.
 */
public abstract class PrePaintableJPanel extends JPanel {
    @Serial
    private static final long serialVersionUID = 1L;

    { // by default, align everything left
        this.setLayout(new FlowLayout(FlowLayout.LEFT));
    }

    /**
     * Recalculate the contents of each Component, maybe slowly, so that the next repaint() will trigger
     * a fast render of everything. If this contains any other UpdatableJPanels, then it must call their
     * prePaint(), too.
     */
    public abstract void prePaint();
}
