// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph;

import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import com.swirlds.platform.gui.hashgraph.internal.CachingGuiSource;
import com.swirlds.platform.gui.hashgraph.internal.HashgraphGuiControls;
import com.swirlds.platform.gui.hashgraph.internal.HashgraphPicture;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ItemEvent;
import java.io.Serial;
import javax.swing.JPanel;

/**
 * A {@link JPanel} that draws a hashgraph from a provided source, as well as having controls to modify display options
 */
public class HashgraphGui extends PrePaintableJPanel {
    /** needed for serializing */
    @Serial
    private static final long serialVersionUID = 1L;

    /** the panel that has the picture of the hashgraph */
    private final HashgraphPicture picturePanel;

    private final CachingGuiSource hashgraphSource;
    private final HashgraphGuiControls controls;
    private boolean sourceReadyCheckDone = false;

    /**
     * Instantiate and initialize content of this tab.
     */
    public HashgraphGui(final HashgraphGuiSource hashgraphSource) {
        this.hashgraphSource = new CachingGuiSource(hashgraphSource);

        // the tab contains the pairPanel, which contains:
        // at (0,0) checkboxesPanel (with weight 0)
        // at (1,0) picture (which is the hashgraph) (with weight 1)

        controls = new HashgraphGuiControls(this::freezeChanged);

        /////////////////// create checkboxesPanel ///////////////////

        /* the panel with checkboxes */
        final JPanel checkboxesPanel = controls.createPanel();

        /////////////////// create picture ///////////////////

        picturePanel = new HashgraphPicture(this.hashgraphSource, controls);
        picturePanel.setLayout(new GridBagLayout());
        picturePanel.setBackground(Color.WHITE);
        picturePanel.setVisible(true);

        /////////////////// create pairPanel (contains checkboxesPanel, picturePnel) ///////////////////

        final JPanel pairPanel = GuiUtils.createPairPanel(checkboxesPanel, picturePanel);

        /////////////////// add everything to this ///////////////////

        setLayout(new GridBagLayout()); // put panel at top, then spacer below it
        final GridBagConstraints c4 = new GridBagConstraints();
        c4.anchor = GridBagConstraints.FIRST_LINE_START;
        c4.gridx = 0;
        c4.gridy = 0;
        c4.weightx = 1.0f;
        c4.weighty = 1.0f;
        c4.gridwidth = GridBagConstraints.REMAINDER;
        add(pairPanel, c4);
        picturePanel.setVisible(true);

        final Dimension ps = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        ps.width -= 150;
        ps.height -= 200;
        pairPanel.setPreferredSize(ps);

        this.hashgraphSource.refresh();
        sourceReadyCheck();
        revalidate();
    }

    /**
     * A method that is called when the freeze option in the controls has changed
     */
    public void freezeChanged(final ItemEvent e) {
        picturePanel.freezeChanged(e);
    }

    @Override
    public void prePaint() {
        reloadSource();
    }

    public void reloadSource() {
        sourceReadyCheck();
        if (!hashgraphSource.isReady() || controls.isPictureFrozen()) {
            return; // freeze when requested, or when it hasn't been created yet
        }
        // getAllEvents may be a slow operation, so do it here not in paintComponent
        hashgraphSource.refresh();

        // The drawing will have to be redone from scratch every time,
        // so not much is done in prePaint(). Most things are done in paintComponent().
    }

    private void sourceReadyCheck() {
        if (!sourceReadyCheckDone && hashgraphSource.isReady()) {
            sourceReadyCheckDone = true;
        }
    }
}
