// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.gui.GuiUtils.wrap;

import com.swirlds.common.metrics.platform.AbstractDistributionMetric;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.gui.GuiConstants;
import com.swirlds.platform.gui.GuiUtils;
import com.swirlds.platform.gui.components.Chart;
import com.swirlds.platform.gui.components.ChartLabelModel;
import com.swirlds.platform.gui.components.PrePaintableJPanel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class WinTab2Stats extends PrePaintableJPanel implements ChartLabelModel {
    private static final long serialVersionUID = 1L;
    public static final int CHART_WIDTH = 750;
    public static final int CHART_HEIGHT = 400;

    private final transient List<Metric> metrics;
    /** number of columns of statistics boxes to display */
    final int numCols = 8;
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(WinTab2Stats.class);
    /** a sentence describing each stat */
    private JTextArea descriptions;

    private final Color LIGHT_GRAY = new Color(0.9f, 0.9f, 0.9f);

    /** all the TextArea objects, one per statistic */
    private JLabel[] statBoxes;
    /** strings describing each statistic (name, description, formatting string) */
    private JPanel[] charts;
    /** the panel containing all the stats boxes (one name, one stat each) */
    private JPanel boxesPanel;
    /** the panel containing all the visible charts (if any) */
    private JPanel chartsPanel;
    /** the labels to put on the X axis */
    private String[] xLabel;
    /** the value of each label to put on the X axis */
    private double[] xLabelVal;
    /** the labels to put on the Y axis */
    private String[] yLabel;
    /** the value of each label to put on the Y axis */
    private double[] yLabelVal;
    /** the nth box in row major order is the rm2cm[n] box in column major */
    private int[] rm2cm;
    /** the nth box in column major order is the cm2rm[n] box in row major */
    private int[] cm2rm;

    /**
     * Instantiate and initialize content of this tab.
     *
     * @param guiMetrics the metrics to use (from the first local node
     */
    public WinTab2Stats(@NonNull final Metrics guiMetrics) {
        metrics = guiMetrics.getAll().stream()
                .sorted(Comparator.comparing(m -> m.getName().toUpperCase()))
                .toList();
        int numStats = this.metrics.size();
        // numRows is: ceiling of numStats / numCols
        int numRows = numStats / numCols + ((numStats % numCols == 0) ? 0 : 1);
        // lastTall is: (numStats-1) mod numCols
        int lastTall = ((numStats - 1 + numCols) % numCols);
        rm2cm = new int[numStats]; // position i in row major order is position rm2cm[i] in column major
        cm2rm = new int[numStats]; // position j in column major order is position cm2rm[j] in row major
        for (int i = 0; i < numStats; i++) {
            int row = i / numCols;
            int col = i % numCols;
            int j = row + col * numRows - (col - 1 > lastTall ? col - 1 - lastTall : 0);
            rm2cm[i] = j;
            cm2rm[j] = i;
        }
    }

    /**
     * make visible the chart with the given index, and turn its box blue. This is also the index of the statistic that
     * it shows.
     *
     * @param index the index of the chart, which is also the index of the statistic
     */
    void showChart(int index) {
        int j = cm2rm[index];
        chartsPanel.add(charts[index]);
        charts[index].setVisible(true); // flag this as having been added
        statBoxes[j].setBackground(GuiConstants.MEMBER_HIGHLIGHT_COLOR);
        revalidate();
    }

    /**
     * make invisible the chart with the given index, and turn its box white. This is also the index of the statistic
     * that it shows.
     *
     * @param index the index of the chart, which is also the index of the statistic
     */
    void hideChart(int index) {
        int j = cm2rm[index];
        chartsPanel.remove(charts[index]);
        charts[index].setVisible(false); // flag this as having been removed
        statBoxes[j].setBackground(Color.WHITE);
        revalidate();
    }

    public void prePaint() {
        if (WinBrowser.memberDisplayed == null) {
            return; // the screen is blank until we choose who to display
        }

        if (statBoxes != null) {
            // set up every Component on the screen, but only once
            return;
        }
        boxesPanel = new JPanel(new GridBagLayout());
        chartsPanel = new JPanel();
        BoxLayout boxLayout = new BoxLayout(chartsPanel, BoxLayout.Y_AXIS);
        chartsPanel.setLayout(boxLayout);

        setLayout(new GridBagLayout());
        boxesPanel.setBackground(Color.WHITE);
        chartsPanel.setBackground(Color.WHITE);
        setBackground(Color.WHITE);

        JTextArea instructions = GuiUtils.newJTextArea("");
        JTextArea spacer = GuiUtils.newJTextArea("");
        instructions.setText(wrap(
                50,
                ""
                        + "The table shows various statistics about how the system is running. "
                        + "Click on a white square to plot the history of the statistic. "
                        + "Click on a highlighted square to hide that plot. \n\n"
                        + "Each plot shows history divided into periods. Within one period, the blue line goes through "
                        + "the average value during that period. The light gray box shows the min and max value during "
                        + "that period. The dark gray box shows one standard deviation above and below the mean."));

        final int numStats = metrics.size();
        statBoxes = new JLabel[numStats];
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        c.anchor = GridBagConstraints.FIRST_LINE_START; // grid (0,0) is upper-left corner
        c.gridx = 0;
        c.gridy = 0;
        charts = new JPanel[numStats];
        for (int i = 0; i < numStats; i++) { // i goes through them in row major order
            int j = rm2cm[i]; // j goes through them in column major order
            final Metric metric = metrics.get(j);
            JLabel txt = new JLabel();
            txt.setToolTipText(metric.getDescription());
            txt.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.BLACK), BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            statBoxes[i] = txt;

            c.gridx = i % numCols;
            c.gridy = i / numCols;
            c.gridwidth = 1; // not end of row
            c.weightx = 0;
            boxesPanel.add(txt, c);
            if (i % numCols == numCols - 1) {
                JTextArea spacer2 = GuiUtils.newJTextArea("");
                c.gridx++;
                c.weightx = 1.0f;
                c.gridwidth = GridBagConstraints.REMAINDER; // end of row
                boxesPanel.add(spacer2, c);
            }
            if (metric instanceof AbstractDistributionMetric statMetric
                    && statMetric.getStatsBuffered().getAllHistory() != null) {
                statBoxes[i].setBackground(Color.WHITE);
                statBoxes[i].setOpaque(true);
                charts[j] = new JPanel();
                charts[j].setBackground(Color.WHITE);
                charts[j].add(new Chart(
                        this, CHART_WIDTH, CHART_HEIGHT, false, statMetric.getStatsBuffered(), metric.getName()));
                charts[j].add(new Chart(
                        this, CHART_WIDTH, CHART_HEIGHT, true, statMetric.getStatsBuffered(), metric.getName()));
                charts[j].setVisible(false); // use "visible" as a flag to indicate it's in the container
                final int jj = j; // effectively final so the listener can use it
                statBoxes[i].addMouseListener(new MouseAdapter() {
                    public void mousePressed(MouseEvent e) {
                        if (charts[jj].isVisible()) {
                            hideChart(jj);
                        } else {
                            showChart(jj);
                        }
                    }
                });
            } else {
                // if no history, then box is gray, and not clickable
                statBoxes[i].setBackground(LIGHT_GRAY);
                statBoxes[i].setOpaque(true);
            }

            if ("trans_per_sec".equals(metric.getName())) {
                showChart(j);
            }
        }
        descriptions = GuiUtils.newJTextArea("");
        descriptions.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        final StringBuilder s = new StringBuilder();
        for (final Metric metric : metrics) {
            s.append(String.format("%17s: %s%n", metric.getName(), metric.getDescription()));
        }
        descriptions.setText(s.toString());
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 1;
        c.gridheight = 1;
        c.insets = new Insets(10, 10, 10, 10);
        add(boxesPanel, c);
        c.gridy = 1;
        add(spacer, c);
        c.gridwidth = GridBagConstraints.REMAINDER; // end of row
        c.gridx = 1;
        c.gridheight = 2;
        c.gridy = 0;
        add(instructions, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridheight = 1;
        add(chartsPanel, c);
        c.gridy = 3;
        add(descriptions, c);
        JTextArea spacer2 = GuiUtils.newJTextArea("");
        c.gridy = 4;
        c.weighty = 1.0f;
        add(spacer2, c);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (WinBrowser.memberDisplayed == null) {
            return; // the screen is blank until we choose who to display
        }
        for (int i = 0; i < statBoxes.length; i++) {
            int j = rm2cm[i];
            statBoxes[i].setForeground(Color.BLACK);
            final Metric metric = metrics.get(j);
            statBoxes[i].setText("<html><center>&nbsp;&nbsp;&nbsp;&nbsp;"
                    + metric.getName() + "&nbsp;&nbsp;&nbsp;&nbsp;<br>"
                    + format(metric).trim() + "</center></html>");
            statBoxes[i].setHorizontalAlignment(JLabel.CENTER);
        }
    }

    private static String format(final Metric metric) {
        try {
            if (metric == null) {
                return "";
            }
            return String.format(Locale.US, metric.getFormat(), metric.get(ValueType.VALUE));
        } catch (final IllegalFormatException e) {
            logger.error(EXCEPTION.getMarker(), "unable to compute string for {}", metric.getName(), e);
        }
        return "";
    }

    @Override
    public void setYLabels(String[] labels) {
        this.yLabel = labels;
    }

    @Override
    public void setYLabelValues(double[] values) {
        this.yLabelVal = values;
    }

    @Override
    public void setXLabels(String[] labels) {
        this.xLabel = labels;
    }

    @Override
    public void setXLabelValues(double[] values) {
        this.xLabelVal = values;
    }

    @Override
    public String[] getYLabels() {
        return yLabel;
    }

    @Override
    public double[] getYLabelValues() {
        return yLabelVal;
    }

    @Override
    public String[] getXLabels() {
        return xLabel;
    }

    @Override
    public double[] getXLabelValues() {
        return xLabelVal;
    }
}
