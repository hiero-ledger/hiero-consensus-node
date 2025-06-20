// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.gui.GuiUtils.wrap;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getStateHierarchy;

import com.swirlds.platform.gui.GuiConstants;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import com.swirlds.platform.gui.model.InfoApp;
import com.swirlds.platform.gui.model.InfoEntity;
import com.swirlds.platform.gui.model.InfoMember;
import com.swirlds.platform.gui.model.InfoSwirld;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The tab in the Browser window that shows available apps, running swirlds, and saved swirlds.
 */
class WinTabSwirlds extends PrePaintableJPanel {
    private static final long serialVersionUID = 1L;
    private JTextPane lastText = null;
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(WinTabSwirlds.class);

    private JTextPane instructions;
    /** should the next prePaint rebuild the window contents? */
    private boolean redoWindow = true;

    public WinTabSwirlds() {
        GridBagLayout gridbag = new GridBagLayout();
        setLayout(gridbag);
        setFont(GuiConstants.FONT);
        chooseMemberDisplayed();

        instructions = new JTextPane();
        instructions.setText(wrap(
                70,
                "\n\n"
                        + "The above are all of the known apps in the data/apps directory. "
                        + "Under each app is all of the known swirlds (i.e., shared worlds, "
                        + "shared ledgers, shared databases). "
                        + "Under each swirld is all of the known members using this local machine. "
                        + "Each one that is currently active is marked as \"running\". "
                        + "Click on a member to show their information in all the tabs here. "
                        + "Minimize this window to hide it. Close it to quit the entire program."));
        freeze(instructions);
    }

    /** {@inheritDoc} */
    public void prePaint() {
        if (!redoWindow) {
            return;
        }
        redoWindow = false;
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        c.gridwidth = GridBagConstraints.REMAINDER; // end row
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        c.gridx = 0;
        c.gridy = 0;
        for (InfoApp app : getStateHierarchy().apps) {
            addEntity(this, app, c, 0, "", false);
            c.gridy++;
            for (InfoSwirld swirld : app.getSwirlds()) {
                addEntity(this, swirld, c, 1, "", false);
                c.gridy++;
                for (InfoMember member : swirld.getMembers()) {
                    if (WinBrowser.memberDisplayed == null) {
                        setMemberDisplayed(member);
                    }
                    addEntity(this, member, c, 2, "", true);
                    c.gridy++;
                }
            }
        }
        c.weightx = 0.0;
        c.weighty = 0.0;
        c.insets = new Insets(10, 10, 10, 10);
        add(instructions, c);
        c.gridy++;
        if (lastText == null) { // add an invisible spacer that takes up all extra space
            lastText = new JTextPane();
            lastText.setText("");
            freeze(lastText);
            c.weightx = 1.0;
            c.weighty = 1.0;
            add(lastText, c);
            c.gridy++;
        }
    }

    void setMemberDisplayed(InfoMember member) {
        WinBrowser.memberDisplayed = member;
        WinBrowser.nameBarName.setText("    " + member.getName() + "    ");
        for (InfoApp app : getStateHierarchy().apps) {
            for (InfoSwirld swirld : app.getSwirlds()) {
                for (InfoMember mem : swirld.getMembers()) {
                    if (mem.getPanel() != null) {
                        ((EntityRow) mem.getPanel())
                                .setColor(
                                        WinBrowser.memberDisplayed == mem
                                                ? GuiConstants.MEMBER_HIGHLIGHT_COLOR
                                                : Color.WHITE);
                    }
                }
            }
        }
    }

    void addEntity(
            WinTabSwirlds parent,
            InfoEntity entity,
            GridBagConstraints c,
            int level,
            String suffix,
            boolean selectable) {
        if (entity.getPanel() != null) { // ignore if it has already been added
            return;
        }
        EntityRow row = new EntityRow(entity, level, suffix, selectable);
        row.setColor((WinBrowser.memberDisplayed == entity) ? GuiConstants.MEMBER_HIGHLIGHT_COLOR : Color.WHITE);
        add(row, c);
    }

    /** when clicked on, set the entity displayed to be this entity */
    private class ClickToSelect extends MouseAdapter {
        InfoMember member;

        ClickToSelect(InfoEntity member) {
            if (!(member instanceof InfoMember)) {
                // only entities representing a Member should be clickable to select as
                // WinBrowser.memberDisplayed
                logger.error(
                        EXCEPTION.getMarker(),
                        "WinTabSwirlds.ClickToSelect instantiated with {} which is not an InfoMember",
                        member);
                return;
            }
            this.member = (InfoMember) member;
        }

        public void mousePressed(MouseEvent mouseEvent) {
            if (mouseEvent.getButton() == MouseEvent.BUTTON1) {
                setMemberDisplayed(member);
            }
        }
    }

    /** a JPanel that represents one App, Swirld, or Member, which is one row in the Swirlds tab */
    class EntityRow extends JPanel {
        /** needed for serializing */
        private static final long serialVersionUID = 1L;
        /** the app, swirld, member, or state represented by this JPanel */
        InfoEntity member;
        /** amount to indent (0 = none) */
        int level;

        JPanel indent;
        JTextPane name;
        JTextPane suf;

        /** set the color of the row, name, and suffix. But leave the indentation white */
        void setColor(Color color) {
            this.setBackground(Color.WHITE);
            indent.setBackground(Color.WHITE);
            name.setBackground(color);
            suf.setBackground(color);
        }

        /**
         * Create a JPanel representing the entity, and give the entity a reference to it.
         *
         * @param entity
         * 		the entity to display
         * @param level
         * 		number of levels to indent (0 to not indent)
         * @param suffix
         * 		a string to add at the end of the panel (on the right)
         * @param selectable
         * 		true if this row can be selected as the member displayed
         */
        EntityRow(InfoEntity entity, int level, String suffix, boolean selectable) {
            this.member = entity;
            this.level = level;
            indent = new JPanel();
            name = new JTextPane();
            suf = new JTextPane();

            if (selectable) {
                indent.addMouseListener(new ClickToSelect(entity));
                name.addMouseListener(new ClickToSelect(entity));
                suf.addMouseListener(new ClickToSelect(entity));
                addMouseListener(new ClickToSelect(entity));
            }

            this.setLayout(new FlowLayout(FlowLayout.LEADING, 0, 2));
            indent.setPreferredSize(new Dimension(30 * level, 10));
            indent.setMinimumSize(new Dimension(30 * level, 10));
            name.setText("    " + entity.getName() + "    ");
            freeze(name);
            freeze(suf);
            suf.setText(suffix);
            add(indent);
            add(name);
            add(suf);
            entity.setPanel(this);
        }
    }

    void freeze(JTextPane text) {
        text.setFont(GuiConstants.FONT);
        text.setEditable(false);
        text.setEnabled(false);
        text.setBackground(Color.WHITE);
        text.setForeground(Color.BLACK);
        text.setDisabledTextColor(Color.BLACK);
    }

    void chooseMemberDisplayed() {
        // If there is no name at the top of the browser window (above the tab buttons),
        // then put the first member there
        try { // if there is concurrent modification, then do nothing, and try again later
            for (InfoApp app : getStateHierarchy().apps) {
                for (InfoSwirld swirld : app.getSwirlds()) {
                    for (InfoMember member : swirld.getMembers()) {
                        if (WinBrowser.memberDisplayed == null) {
                            this.setMemberDisplayed(member);
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
    }
}
