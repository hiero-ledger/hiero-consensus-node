// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getBrowserWindow;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.showBrowserWindow;

import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.gui.GuiConstants;
import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.components.ScrollableJPanel;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.gui.model.InfoMember;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * The main browser window. It contains a tabbed pane, which contains the classes with names WinTab*, some of which
 * might themselves contain tabbed panes, whose tabs would be classes named WinTab2*, and so on.
 * <p>
 * The classes WinBrowser, ScrollableJPanel, and UpdatableJPanel, each have an prePaint() method. Once a second, a timer
 * thread calls WinBrowser.prePaint, and then the call is passed down the Component tree, so that every Component can do
 * the slow calculations for what should appear on the screen. Then the thread calls repaint(), to quickly re-render
 * everything. For example, if there is a long calculation necessary to find the text for a JTextArea, then the long
 * calculation is done inside prePaint(), and the fast rendering of the result is done in paintComponent(). This
 * prevents the GUI thread from hanging for too long when there are slow calculations being performed.
 */
public class WinBrowser extends JFrame {
    /** needed to serializing */
    private static final long serialVersionUID = 1L;
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(WinBrowser.class);

    /** refresh the screen every this many milliseconds */
    final int refreshPeriod = 500;
    /** the InfoMember that is currently being shown by all tabs in the browser window */
    public static volatile InfoMember memberDisplayed = null;
    /** have all the tabs been initialized yet? */
    private boolean didInit = false;

    /** used to refresh the screen periodically */
    private static Timer updater = null;
    /** gap at top of the screen (to let you click on app windows), in pixels */
    private static final int topGap = 40;

    static ScrollableJPanel tabSwirlds;
    static ScrollableJPanel tabAddresses;
    static ScrollableJPanel tabCalls;
    static ScrollableJPanel tabPosts;
    static ScrollableJPanel tabNetwork;
    static ScrollableJPanel tabSecurity;

    static JPanel nameBar;
    static JTextArea nameBarLabel;
    /** the nickname and name of the member on local machine currently being viewed */
    static JTextArea nameBarName;

    static JTabbedPane tabbed;

    public void paintComponents(Graphics g) {
        super.paintComponents(g);
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
    }

    /** when clicked on, switch to the Swirlds tab in the browser window */
    private class ClickForTabSwirlds extends MouseAdapter {
        public void mouseClicked(MouseEvent mouseEvent) {
            if (mouseEvent.getButton() == MouseEvent.BUTTON1) { // 3 for right click
                showBrowserWindow(WinBrowser.tabSwirlds);
            }
        }
    }

    /**
     * Perform a prePaint to recalculate the contents of each Component, maybe slowly, so that the next repaint() will
     * trigger a fast render of everything. Then perform a repaint(). This is synchronized because it is called by a
     * timer once a second, and is also called by the thread that manages the mouse whenever a user changes a tab in
     * this window or changes a tab in the Network tab.
     */
    static synchronized void prePaintThenRepaint() {
        try {
            // Don't prePaint nameBar, nameBarLabel, tabbed, or any tab*.

            // perform the equivalent of prePaint on nameBarName:
            if (WinBrowser.memberDisplayed != null) {
                nameBarName.setText("    " + WinBrowser.memberDisplayed.getName() + "    ");
            } else { // retry once a second until at least one member exists. Then choose the first one.
                if (tabSwirlds != null) {
                    ((WinTabSwirlds) tabSwirlds.getContents()).chooseMemberDisplayed();
                }
            }
            // call prePaint() on the current tab, only if it has such a method
            if (tabbed != null) {
                Component comp = tabbed.getSelectedComponent();
                if (comp != null) {
                    if (comp instanceof ScrollableJPanel) {
                        ScrollableJPanel tabCurrent = (ScrollableJPanel) comp;
                        tabCurrent.prePaint();
                    }
                }
            }
            WinBrowser win = getBrowserWindow();
            if (win != null) {
                if (!win.didInit
                        && WinBrowser.memberDisplayed != null
                        && tabSwirlds != null
                        && tabAddresses != null
                        && tabCalls != null
                        && tabPosts != null
                        && tabNetwork != null
                        && tabSecurity != null) {
                    win.didInit = true;
                    // just once, do an init that does prePaint for everyone, so when we switch tabs, it
                    // appears
                    // instantly
                    tabSwirlds.prePaint();
                    tabAddresses.prePaint();
                    tabCalls.prePaint();
                    tabPosts.prePaint();
                    tabNetwork.prePaint();
                    tabSecurity.prePaint();
                }
                win.repaint();
            }
        } catch (Exception e) {
            logger.error(EXCEPTION.getMarker(), "error while prepainting or painting: ", e);
        }
    }

    /**
     * This constructor creates the contents of the browser window, and creates a new thread to continually update this
     * window to reflect what is happening in the Browser.
     *
     * @param firstNodeId     the ID of the first node running on this machine, information from this node will be shown
     *                        in the UI
     * @param hashgraphSource provides access to events
     * @param consensus       a local view of the hashgraph
     * @param guiMetrics      provides access to metrics
     */
    public WinBrowser(
            @NonNull final NodeId firstNodeId,
            @NonNull final HashgraphGuiSource hashgraphSource,
            @NonNull final Consensus consensus,
            @NonNull final Metrics guiMetrics) {
        ActionListener repaintPeriodically = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                // Do a (possibly slow) prePaint of components, like changing text in a JTextArea text.
                // Then trigger a (fast) redrawing of the components, like rendering the JTextArea text.
                prePaintThenRepaint();
            }
        };

        nameBar = new JPanel();
        nameBarLabel = new JTextArea();
        nameBarName = new JTextArea();
        tabbed = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.WRAP_TAB_LAYOUT);
        tabSwirlds = GuiUtils.makeScrollableJPanel(new WinTabSwirlds());
        tabAddresses = GuiUtils.makeScrollableJPanel(new WinTabAddresses());
        tabCalls = GuiUtils.makeScrollableJPanel(new WinTabCalls());
        tabPosts = GuiUtils.makeScrollableJPanel(new WinTabPosts());
        tabNetwork =
                GuiUtils.makeScrollableJPanel(new WinTabNetwork(firstNodeId, hashgraphSource, consensus, guiMetrics));
        tabSecurity = GuiUtils.makeScrollableJPanel(new WinTabSecurity());

        Rectangle winRect = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Dimension dim = new Dimension(winRect.width, winRect.height - topGap);
        setSize(dim);
        setPreferredSize(dim);

        setLocation(winRect.x, winRect.y + topGap);
        setFocusable(true);
        requestFocusInWindow();
        addWindowListener(GuiUtils.stopper());

        nameBar.setLayout(new GridBagLayout());
        nameBar.setPreferredSize(new Dimension(1000, 42));

        MouseListener listener = new ClickForTabSwirlds();

        nameBarLabel.setText("Displaying information for:  ");
        nameBarLabel.setEditable(false);
        nameBarLabel.setEnabled(false);
        nameBarLabel.setDisabledTextColor(Color.BLACK);
        nameBarLabel.addMouseListener(listener);
        nameBarLabel.setCaretPosition(0);

        nameBarName.setText(""); // this will be set again each time the member is chosen
        nameBarName.setEditable(false);
        nameBarName.setEnabled(false);
        nameBarName.setDisabledTextColor(Color.BLACK);
        nameBarName.addMouseListener(listener);

        nameBar.add(nameBarLabel);
        nameBar.add(nameBarName);
        nameBar.addMouseListener(listener);
        tabbed.addTab("Swirlds", tabSwirlds);
        // tabbed.addTab("Calls", tabCalls);
        // tabbed.addTab("Posts", tabPosts);
        tabbed.addTab("Addresses", tabAddresses);
        tabbed.addTab("Network", tabNetwork);
        tabbed.addTab("Security", tabSecurity);
        goTab(tabNetwork); // select and show this one first

        setBackground(Color.WHITE); // this color flashes briefly at startup, then is hidden
        nameBar.setBackground(Color.WHITE); // color of name bar outside label and name
        nameBarLabel.setBackground(Color.WHITE); // color of name bar label
        nameBarName.setBackground(GuiConstants.MEMBER_HIGHLIGHT_COLOR); // color of name
        tabbed.setBackground(Color.WHITE); // color of non-highlighted tab buttons
        tabbed.setForeground(Color.BLACK); // color of words on the tab buttons

        setLayout(new BorderLayout());
        add(nameBar, BorderLayout.PAGE_START);
        add(tabbed, BorderLayout.CENTER);
        // add(tabPosts, BorderLayout.CENTER);
        SwirldMenu.addTo(null, this, 40);
        pack();
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        setVisible(true);

        updater = new Timer(refreshPeriod, repaintPeriodically);
        updater.start();
    }

    /**
     * Switch to the tab containing the given contents, and bring the window forward.
     *
     * @param contents the contents of that tab
     */
    public void goTab(ScrollableJPanel contents) {
        requestFocus(true);
        if (contents != null) {
            tabbed.setSelectedComponent(contents);
        }
        prePaintThenRepaint();
    }
}
