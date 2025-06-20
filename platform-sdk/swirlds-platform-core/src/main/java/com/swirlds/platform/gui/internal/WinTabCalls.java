// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import com.swirlds.platform.gui.GuiConstants;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import javax.swing.JLabel;

/**
 * The tab in the Browser window that shows available apps, running swirlds, and saved swirlds.
 */
class WinTabCalls extends PrePaintableJPanel {
    private static final long serialVersionUID = 1L;

    public WinTabCalls() {
        JLabel label = new JLabel("There are no recent calls.");
        label.setFont(GuiConstants.FONT);
        add(label);
    }

    /** {@inheritDoc} */
    public void prePaint() {}
}
