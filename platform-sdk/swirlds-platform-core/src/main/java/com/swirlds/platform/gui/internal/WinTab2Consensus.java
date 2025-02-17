/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.gui.internal;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Font;
import java.awt.Graphics;
import java.util.Objects;
import javax.swing.JTextArea;

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
