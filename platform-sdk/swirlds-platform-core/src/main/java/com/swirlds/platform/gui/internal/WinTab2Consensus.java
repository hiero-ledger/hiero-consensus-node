// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import com.swirlds.platform.Consensus;
import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Font;
import java.awt.Graphics;
import java.util.Objects;
import javax.swing.JTextArea;
import org.hiero.consensus.model.node.NodeId;

/**
 * The tab in the Browser window that shows network speed, transactions per second, etc.
 */
class WinTab2Consensus extends PrePaintableJPanel {
    /** this is needed for serializing */
    private static final long serialVersionUID = 1L;

    /** the entire table is in this single Component */
    private JTextArea text;

    private final Consensus consensus;
    private final NodeId firstNodeId;

    /**
     * Instantiate and initialize content of this tab.
     */
    public WinTab2Consensus(@NonNull final Consensus consnesus, @NonNull final NodeId firstNodeId) {
        text = GuiUtils.newJTextArea("");
        this.consensus = Objects.requireNonNull(consnesus);
        this.firstNodeId = Objects.requireNonNull(firstNodeId);
        add(text);
    }

    /** {@inheritDoc} */
    @Override
    public void prePaint() {
        try {
            if (WinBrowser.memberDisplayed == null) {
                return;
            }
            String s = "";
            s += "Node" + firstNodeId.id();
            final long rd = consensus.getFameDecidedBelow();
            final long rc = consensus.getMaxRound();

            s += String.format("\n%,10d = latest round-decided", rd);
            s += String.format("\n%,10d = latest round-created", rc);

            text.setFont(new Font("monospaced", Font.PLAIN, 14));

            text.setText(s);
        } catch (final java.util.ConcurrentModificationException err) {
            // We started displaying before all the platforms were added. That's ok.
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
    }
}
