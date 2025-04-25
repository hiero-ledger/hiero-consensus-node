// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import static com.swirlds.platform.gui.GuiUtils.wrap;
import static com.swirlds.platform.gui.hashgraph.HashgraphGuiConstants.DEFAULT_GENERATIONS_TO_DISPLAY;

import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.hashgraph.HashgraphPictureOptions;
import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.hiero.consensus.model.event.EventConstants;

/**
 * GUI controls for changing display options for the {@link HashgraphPicture}
 */
public class HashgraphGuiControls implements HashgraphPictureOptions {
    /** if checked, freeze the display (don't update it) */
    private final Checkbox freezeCheckbox;
    /** if checked, color vertices only green (non-consensus) or blue (consensus) */
    private final Checkbox simpleColorsCheckbox;
    /** if checked, use multiple columns per member to void lines crossing */
    private final Checkbox expandCheckbox;

    // the following control which labels to print on each vertex

    /** the round number for the event */
    private final Checkbox labelRoundCheckbox;
    /** votes for the witnesses */
    private final Checkbox labelVoteCheckbox;
    /** the hash for the event */
    private final Checkbox labelEventHashCheckbox;
    /** the consensus round received for the event */
    private final Checkbox labelRoundRecCheckbox;
    /** the consensus order number for the event */
    private final Checkbox labelConsOrderCheckbox;
    /** the consensus time stamp for the event */
    private final Checkbox labelConsTimestampCheckbox;
    /** the generation number for the event */
    private final Checkbox labelGenerationCheckbox;
    /** the birth round number for the event */
    private final Checkbox labelBirthroundCheckbox;
    /** the branch number for the event */
    private final Checkbox labelBranchNumberCheckbox;
    /** the DeGen value for the event */
    private final Checkbox labelDeGenCheckbox;
    /** check to display the latest events available */
    private final Checkbox displayLatestEvents;

    private final Component[] comps;
    /** only draw this many generations, at most */
    private final JSpinner numGenerations;

    private final JSpinner startGeneration;

    public HashgraphGuiControls(final ItemListener freezeListener) {
        freezeCheckbox = new Checkbox("Freeze: don't change this window");
        freezeCheckbox.addItemListener(freezeListener);
        simpleColorsCheckbox = new Checkbox("Colors: blue=consensus, green=not");
        expandCheckbox = new Checkbox("Expand: wider so lines don't cross");
        labelRoundCheckbox = new Checkbox("Labels: Round created");
        labelVoteCheckbox = new Checkbox("Labels: Vote");
        labelEventHashCheckbox = new Checkbox("Labels: Event Hash (h)");
        labelRoundRecCheckbox = new Checkbox("Labels: Round received (consensus)");
        labelConsOrderCheckbox = new Checkbox("Labels: Order (consensus)");
        labelConsTimestampCheckbox = new Checkbox("Labels: Timestamp (consensus)");
        labelGenerationCheckbox = new Checkbox("Labels: Generation");
        labelBirthroundCheckbox = new Checkbox("Labels: Birth round");
        labelBranchNumberCheckbox = new Checkbox("Labels: Branch number");
        labelDeGenCheckbox = new Checkbox("Labels: DeGen");
        displayLatestEvents = new Checkbox("Display latest events");
        displayLatestEvents.setState(true);

        // boxing so that the JSpinner will use an int internally
        numGenerations = new JSpinner(new SpinnerNumberModel(
                Integer.valueOf(DEFAULT_GENERATIONS_TO_DISPLAY),
                Integer.valueOf(5),
                Integer.valueOf(1000),
                Integer.valueOf(1)));
        ((JSpinner.DefaultEditor) numGenerations.getEditor()).getTextField().setColumns(10);
        // boxing so that the JSpinner will use a long internally
        startGeneration = new JSpinner(new SpinnerNumberModel(
                Long.valueOf(EventConstants.FIRST_GENERATION),
                Long.valueOf(EventConstants.FIRST_GENERATION),
                Long.valueOf(Long.MAX_VALUE),
                Long.valueOf(1)));
        ((JSpinner.DefaultEditor) startGeneration.getEditor()).getTextField().setColumns(10);
        startGeneration.setEnabled(false);

        displayLatestEvents.addItemListener(e -> {
            switch (e.getStateChange()) {
                case ItemEvent.SELECTED -> startGeneration.setEnabled(false);
                case ItemEvent.DESELECTED -> startGeneration.setEnabled(true);
            }
        });

        comps = new Component[] {
            freezeCheckbox,
            simpleColorsCheckbox,
            expandCheckbox,
            labelRoundCheckbox,
            labelVoteCheckbox,
            labelEventHashCheckbox,
            labelRoundRecCheckbox,
            labelConsOrderCheckbox,
            labelConsTimestampCheckbox,
            labelGenerationCheckbox,
            labelBirthroundCheckbox,
            labelBranchNumberCheckbox,
            labelDeGenCheckbox,
            displayLatestEvents
        };
    }

    /**
     * Set the state of the expanded control
     *
     * @param expand
     * 		the state to set it to
     */
    public void setExpanded(final boolean expand) {
        expandCheckbox.setState(expand);
    }

    /**
     * @return a {@link JPanel} with the controls
     */
    public JPanel createPanel() {
        final JPanel checkboxesPanel = new JPanel();
        checkboxesPanel.setLayout(new GridBagLayout());
        checkboxesPanel.setBackground(Color.WHITE);
        checkboxesPanel.setVisible(true);
        final GridBagConstraints constr = new GridBagConstraints();
        constr.fill = GridBagConstraints.NONE; // don't stretch components
        constr.anchor = GridBagConstraints.FIRST_LINE_START; // left align each component in its cell
        constr.weightx = 0; // don't put extra space in the middle
        constr.weighty = 0;
        constr.gridx = 0; // start in upper-left cell
        constr.gridy = 0;
        constr.insets = new Insets(0, 10, -4, 0); // add external padding on left, remove from bottom
        constr.gridheight = 1;
        constr.gridwidth = GridBagConstraints.RELATIVE; // first component is only second-to-last-on-row
        for (final Component c : comps) {
            checkboxesPanel.add(c, constr);
            constr.gridwidth = GridBagConstraints.REMAINDER; // all but the first are last-on-row
            constr.gridy++;
        }
        checkboxesPanel.add(new Label(" "), constr); // skip a line

        constr.gridx = 0;
        constr.gridy++;
        constr.gridwidth = GridBagConstraints.REMAINDER;
        checkboxesPanel.add(new Label("NOTE: when typing in values below, hit enter to apply the value"), constr);

        constr.gridx = 0;
        constr.gridy++;
        constr.gridwidth = 1; // each component is one cell
        checkboxesPanel.add(new Label(" "), constr); // skip a line

        constr.gridx = 0;
        constr.gridy++;
        constr.gridwidth = 1; // each component is one cell
        checkboxesPanel.add(new Label("Display "), constr);
        constr.gridx++;
        checkboxesPanel.add(numGenerations, constr);
        constr.gridx++;
        constr.gridwidth = GridBagConstraints.RELATIVE;
        checkboxesPanel.add(new Label(" generations"), constr);
        constr.gridx++;
        constr.gridwidth = GridBagConstraints.REMAINDER;
        checkboxesPanel.add(new Label(""), constr);

        constr.gridx = 0;
        constr.gridy++;
        constr.gridwidth = 1; // each component is one cell
        checkboxesPanel.add(new Label("Start generation "), constr);
        constr.gridx++;
        checkboxesPanel.add(startGeneration, constr);
        constr.gridx++;
        constr.gridwidth = GridBagConstraints.REMAINDER;
        checkboxesPanel.add(new Label(""), constr);

        constr.gridx = 0;
        constr.gridy++;
        checkboxesPanel.add(new Label(" "), constr);
        constr.gridy++;
        checkboxesPanel.add(
                GuiUtils.newJTextArea(
                        wrap(
                                50,
                                """
                                - Witnesses are colored circles, non-witnesses are black/gray\s
                                - Dark circles are part of the consensus, light are not\s
                                - Judges are blue\s
                                - Non-famous witnesses are yellow\s
                                - Famous witnesses are green\s
                                - Undecided witnesses are red\s
                                - The selected event is magenta with green border\s
                                - The parents of the selected event have magenta borders\s
                                - The events the selected event can strongly see are cyan\s""")),
                constr);
        constr.gridy++;
        constr.weighty = 1.0; // give this spacer all the leftover vertical space in column
        checkboxesPanel.add(new Label(" "), constr); // the spacer that is stretched vertically

        return checkboxesPanel;
    }

    @Override
    public boolean isPictureFrozen() {
        return freezeCheckbox.getState();
    }

    @Override
    public boolean isExpanded() {
        return expandCheckbox.getState();
    }

    @Override
    public boolean writeRoundCreated() {
        return labelRoundCheckbox.getState();
    }

    @Override
    public boolean writeVote() {
        return labelVoteCheckbox.getState();
    }

    @Override
    public boolean writeEventHash() {
        return labelEventHashCheckbox.getState();
    }

    @Override
    public boolean writeRoundReceived() {
        return labelRoundRecCheckbox.getState();
    }

    @Override
    public boolean writeConsensusOrder() {
        return labelConsOrderCheckbox.getState();
    }

    @Override
    public boolean writeConsensusTimeStamp() {
        return labelConsTimestampCheckbox.getState();
    }

    @Override
    public boolean writeGeneration() {
        return labelGenerationCheckbox.getState();
    }

    @Override
    public boolean writeBirthRound() {
        return labelBirthroundCheckbox.getState();
    }

    @Override
    public boolean writeDeGen() {
        return labelDeGenCheckbox.getState();
    }

    @Override
    public boolean simpleColors() {
        return simpleColorsCheckbox.getState();
    }

    @Override
    public int getNumGenerationsDisplay() {
        if (numGenerations.getValue() instanceof Integer generations) {
            return generations;
        }
        return DEFAULT_GENERATIONS_TO_DISPLAY;
    }

    @Override
    public long getStartGeneration() {
        if (startGeneration.getValue() instanceof Long generations) {
            return generations;
        }
        return EventConstants.GENERATION_UNDEFINED;
    }

    @Override
    public boolean displayLatestEvents() {
        return displayLatestEvents.getState();
    }

    @Override
    public boolean writeBranches() {
        return labelBranchNumberCheckbox.getState();
    }

    @Override
    public void setStartGeneration(final long startGeneration) {
        this.startGeneration.setValue(startGeneration);
    }
}
