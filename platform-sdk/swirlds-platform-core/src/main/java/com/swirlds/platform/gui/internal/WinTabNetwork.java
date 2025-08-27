// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import com.swirlds.platform.gui.hashgraph.HashgraphGui;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.hiero.consensus.model.node.NodeId;

/**
 * The tab in the Browser window that shows network speed, transactions per second, etc.
 */
class WinTabNetwork extends PrePaintableJPanel {
    private static final long serialVersionUID = 1L;
    /** have all the tabs been initialized yet? */
    private boolean didInit = false;

    /**
     * a tabbed pane of different views of the network. Should change it from JTabbedPane to something custom that looks
     * nicer
     */
    JTabbedPane tabbed = new JTabbedPane(JTabbedPane.LEFT, JTabbedPane.WRAP_TAB_LAYOUT);

    private final WinTab2Stats tabStats;
    private final HashgraphGui tabHashgraph;
    private final WinTab2Consensus tabConsensus;

    /**
     * Instantiate and initialize content of this tab.
     *
     * @param firstNodeId     the ID of the first node running on this machine, information from this node will be shown
     *                        in the UI
     * @param hashgraphSource provides access to events
     * @param consensus       a local view of the hashgraph
     * @param guiMetrics      provides access to metrics
     */
    public WinTabNetwork(
            @NonNull final NodeId firstNodeId,
            @NonNull final HashgraphGuiSource hashgraphSource,
            @NonNull final Consensus consensus,
            @NonNull final Metrics guiMetrics) {

        tabStats = new WinTab2Stats(guiMetrics);
        tabHashgraph = new HashgraphGui(hashgraphSource);

        tabConsensus = new WinTab2Consensus(consensus, firstNodeId);

        Rectangle winRect = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();

        Dimension d = new Dimension(winRect.width - 20, winRect.height - 110);

        setLocation(winRect.x, winRect.y);
        setFocusable(true);
        requestFocusInWindow();

        tabbed.addTab("Stats", tabStats);
        tabbed.addTab("Hashgraph", tabHashgraph);
        tabbed.addTab("Consensus", tabConsensus);
        tabbed.setSelectedComponent(tabStats); // default to Hashgraph
        tabbed.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (e.getSource() instanceof JTabbedPane) {
                    WinBrowser.prePaintThenRepaint();
                }
            }
        });

        setBackground(Color.WHITE);
        tabbed.setBackground(Color.WHITE);
        tabStats.setBackground(Color.WHITE);
        tabHashgraph.setBackground(Color.WHITE);
        tabConsensus.setBackground(Color.WHITE);

        tabStats.setMinimumSize(d);
        tabHashgraph.setMaximumSize(d);
        tabConsensus.setMaximumSize(d);
        add(tabbed);
        setVisible(true);
        revalidate();
    }

    /** {@inheritDoc} */
    public void prePaint() {
        if (tabbed == null) {
            return;
        }
        if (!didInit && WinBrowser.memberDisplayed != null) {
            didInit = true;
            // just do this once, so we can switch to tabs in the future and see them instantly
            tabStats.prePaint();
            tabHashgraph.prePaint();
            tabConsensus.prePaint();
        }

        Component comp = tabbed.getSelectedComponent();
        if (!(comp instanceof PrePaintableJPanel)) {
            return;
        }
        PrePaintableJPanel panel = (PrePaintableJPanel) comp;
        panel.prePaint();
    }

    /**
     * Switch to tab number n, and bring the window forward.
     *
     * @param n the index of the tab to select
     */
    void goTab(int n) {
        requestFocus(true);
        tabbed.setSelectedIndex(n);
        WinBrowser.prePaintThenRepaint();
    }
}
