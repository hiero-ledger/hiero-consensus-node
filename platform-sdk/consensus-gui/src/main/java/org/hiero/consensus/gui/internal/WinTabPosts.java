// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.internal;

import javax.swing.JLabel;
import org.hiero.consensus.gui.GuiConstants;
import org.hiero.consensus.gui.components.PrePaintableJPanel;

/**
 * The tab in the Browser window that shows available apps, running swirlds, and saved swirlds.
 */
class WinTabPosts extends PrePaintableJPanel {
    private static final long serialVersionUID = 1L;

    public WinTabPosts() {
        JLabel label = new JLabel("There are no recent posts.");
        label.setFont(GuiConstants.FONT);
        add(label);
    }

    /** {@inheritDoc} */
    public void prePaint() {}
}
