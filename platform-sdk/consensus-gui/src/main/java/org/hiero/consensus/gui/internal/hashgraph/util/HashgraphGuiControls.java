// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.internal.hashgraph.util;

import static org.hiero.consensus.gui.internal.GuiUtils.wrap;
import static org.hiero.consensus.gui.internal.hashgraph.HashgraphGuiConstants.DEFAULT_GENERATIONS_TO_DISPLAY;

import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import javax.swing.*;

import org.hiero.consensus.gui.internal.GuiEventStorage;
import org.hiero.consensus.gui.internal.GuiUtils;
import org.hiero.consensus.gui.internal.hashgraph.HashgraphPictureOptions;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo;
import org.hiero.consensus.model.event.EventConstants;

/**
 * GUI controls for changing display options for the {@link HashgraphPicture}
 */
public class HashgraphGuiControls implements HashgraphPictureOptions {
    /** if checked, freeze the display (don't update it) */
    private final Checkbox freezeCheckbox;
    /** if checked, color vertices only green (non-consensus) or blue (consensus) */
    private final Checkbox simpleColorsCheckbox;

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
    /** the Ngen number for the event */
    private final Checkbox labelEventIDCheckbox;
    /** the Ngen number for the event */
    private final Checkbox labelNGenCheckbox;
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

    /** the text block on the left side of the screen, showing fields in the selected event's EventInfo */
    private static JTextArea eventInfoText;

    /** description of the graph image, printed in the left column */
    private final static String ABOUT_TEXT = """
                                - Witnesses are colored circles, non-witnesses are black/gray\s
                                - Dark circles are part of the consensus, light are not\s
                                - Judges are blue\s
                                - Non-judge witnesses are yellow\s
                                - Famous witnesses are green\s
                                - Undecided witnesses are red\s
                                - The selected event is magenta with green border\s
                                - The parents of the selected event have magenta borders\s
                                - The events the selected event can strongly see are cyan\s""";

    public HashgraphGuiControls(final ItemListener freezeListener) {
        freezeCheckbox = new Checkbox("Freeze: don't change this window");
        freezeCheckbox.addItemListener(freezeListener);
        simpleColorsCheckbox = new Checkbox("Colors: blue=consensus, green=not");
        simpleColorsCheckbox.setState(true);
        labelEventIDCheckbox = new Checkbox("Labels: EventID (nGen)");
        labelEventIDCheckbox.setState(true);
        labelNGenCheckbox = new Checkbox("Labels: NGen (non-deterministic generation)");
        labelRoundCheckbox = new Checkbox(GuiEventStorage.USE_DYNAMIC_ADDRESS_BOOK_UPDATE
                ? "Labels: Voting round" : "Labels: Round created");
        labelRoundCheckbox.setState(true);
        labelVoteCheckbox = new Checkbox("Labels: Vote");
        labelEventHashCheckbox = new Checkbox("Labels: Event Hash (h)");
        labelRoundRecCheckbox = new Checkbox("Labels: Round received (consensus)");
        labelConsOrderCheckbox = new Checkbox("Labels: Order (consensus)");
        labelConsTimestampCheckbox = new Checkbox("Labels: Timestamp (consensus)");
        labelBirthroundCheckbox = new Checkbox("Labels: Birth round");
        labelBranchNumberCheckbox = new Checkbox("Labels: Branch number");
        labelDeGenCheckbox = new Checkbox(GuiEventStorage.USE_DYNAMIC_ADDRESS_BOOK_UPDATE
                ? "Labels: gen (dGen)" : "Labels: DeGen");
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
            labelEventIDCheckbox,
            labelNGenCheckbox,
            labelRoundCheckbox,
            labelVoteCheckbox,
            labelEventHashCheckbox,
            labelRoundRecCheckbox,
            labelConsOrderCheckbox,
            labelConsTimestampCheckbox,
            labelBirthroundCheckbox,
            labelBranchNumberCheckbox,
            labelDeGenCheckbox,
            displayLatestEvents
        };
    }

    /** convert an EventInfo[] to a string of the EventID of each element */
    private static String eventInfosToString(HashgraphInfo.EventInfo[] events) {
        String str = "[ ";
        for (HashgraphInfo.EventInfo event : events) {
            str += ((event == null) ? "-" : event.getEventID()) + " ";
        }
        str += "]";
        return str;
    }

    /** convert a boolean[] to a string */
    private static String booleanssToString(boolean[] booleans) {
        String str = "[ ";
        for (boolean b : booleans) {
            str += (b ? "T " : "F ");
        }
        str += "]";
        return str;
    }

    /** convert a long[] to a string */
    private static String longsToString(long[] longs) {
        String str = "[ ";
        for (long n : longs) {
            str += n + " ";
        }
        str += "]";
        return str;
    }

    /** convert an EventInfo to a string that is its event ID, or "-" if null */
    private static String eventID(HashgraphInfo.EventInfo event) {
        return (event == null) ? "-" : ("" + event.getEventID());
    }

    /** display the fields of this EventInfo on the left side of the window */
    public static void printEventInfo(HashgraphInfo.EventInfo eventInfo) {
        if (eventInfo == null) {
            eventInfoText.setText(ABOUT_TEXT);}
        else {
                String str = "";
            try {
                str += "\nSELECTED EVENT ===============================================";
                str += "\n                            ID  " + eventInfo.getEventID();
                str += "\n               hashgraphInfoID  " + eventInfo.getHashgraph().getHashgraphInfoID();
                str += "\n                   timeCreated  " + eventInfo.getTimeCreated();
                str += "\n                       creator  " + eventInfo.getCreator();
                str += "\n                    birthRound  " + eventInfo.getBirthRound();
                str += "\n                          coin  " + eventInfo.getCoin();
                str += "\n                 parentsSigned  " + eventInfosToString(eventInfo.getParentsSigned());
                str += "\n                    selfParent  " + eventID(eventInfo.getSelfParent());
                str += "\n                 ancestorJudge  " + booleanssToString(eventInfo.getAncestorJudge());
                str += "\n                     prevJudge  " + eventInfo.isPrevJudge(); //need prevJudgeDesc
                str += "\n                           gen  " + eventInfo.getGen();
                str += "\n                       lastSee  " + eventInfosToString(eventInfo.getLastSee());
                str += "\n                  stronglySeeP  " + eventInfosToString(eventInfo.getStronglySeeP());
                str += "\n                   votingRound  " + eventInfo.getVotingRound();
                str += "\n             firstSelfWitnessS  " + eventID(eventInfo.getFirstSelfWitnessS());
                str += "\n                 firstWitnessS  " + eventID(eventInfo.getFirstWitnessS());
                str += "\n                 stronglySeeS1  " + eventInfosToString(eventInfo.getStronglySeeS1());
                str += "\n                         voteE  " + eventInfosToString(eventInfo.getVoteE());
                str += "\n                         voteB  " + booleanssToString(eventInfo.getVoteB());
                str += "\n                   isConsensus  " + eventInfo.isConsensus();
                str += "\n                consensusOrder  " + eventInfo.getConsensusOrder();
                str += "\n            consensusTimestamp  " + eventInfo.getConsensusTimestamp();
                str += "\nLATEST ROUND INFO ("
                        + HashgraphInfo.getLatestRoundInfo().pendingRound()
                        + ") ====================================";
                str += "\n                         nodes  " + longsToString(HashgraphInfo.getLatestRoundInfo().nodes());
                str += "\n                         stake  " + longsToString(HashgraphInfo.getLatestRoundInfo().stake());
                str += "\n               seeNum / seeDen  " + HashgraphInfo.getLatestRoundInfo().seeNum();
                str += " / " + HashgraphInfo.getLatestRoundInfo().seeDen();
                str += "\n                     judgeCon1  " + HashgraphInfo.getLatestRoundInfo().judgeCon1();
                str += "\n                  coinInterval  " + HashgraphInfo.getLatestRoundInfo().coinInterval();
                str += "\n     targetNumRoundsNonAncient  " + HashgraphInfo.getLatestRoundInfo().targetNumRoundsNonAncient();
                str += "\n          numRoundsAddressBook  " + HashgraphInfo.getLatestRoundInfo().numRoundsAddressBook();
                str += "\nLATEST ROUND INFO PREV ("
                        + HashgraphInfo.getLatestRoundInfoPrev().pendingRound()
                        + ") ===============================";
                str += "\n                 prevJudgeCon1  " + HashgraphInfo.getLatestRoundInfoPrev().prevJudgeCon1();
                str += "\n                    prevJudges  "
                        + eventInfosToString(HashgraphInfo.getLatestRoundInfoPrev().prevJudges());
                str += "\n              prevJudgesCopied  " + HashgraphInfo.getLatestRoundInfoPrev().prevJudgesCopied();
                str += "\n        prevMinNonAncientRound  "
                        + HashgraphInfo.getLatestRoundInfoPrev().prevMinNonAncientRound();
                str += "\n                   prevNumCons  "
                        + HashgraphInfo.getLatestRoundInfoPrev().prevNumCons();
                str += "\n        prevMinJudgeBirthRound  "
                        + HashgraphInfo.getLatestRoundInfoPrev().prevMinJudgeBirthRound();
            } catch (Exception e) {
                str = "\n" + e.toString() + "\n" + e.getMessage();
            }
            eventInfoText.setText(str);
        }
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
        eventInfoText = GuiUtils.newJTextArea(wrap(50, ABOUT_TEXT));
        eventInfoText.setFont(new Font(Font.MONOSPACED,Font.PLAIN, 10));
        eventInfoText.setText(ABOUT_TEXT);
        checkboxesPanel.add(eventInfoText, constr);
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
    public boolean writeEventID() {
        return labelEventIDCheckbox.getState();
    }

    @Override
    public boolean writeNGen() {
        return labelNGenCheckbox.getState();
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
