// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.components;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.common.metrics.statistics.internal.StatsBuffer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.*;
import java.util.Objects;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** a JPanel with one chart that plots a statistic vs. time, either all history or recent */
public class Chart extends JPanel {
    /** for serializing */
    private static final long serialVersionUID = 1L;

    private static final Logger logger = LogManager.getLogger(Chart.class);

    private final Color DARK_GRAY = new Color(0.8f, 0.8f, 0.8f);

    private final Color LIGHT_GRAY = new Color(0.9f, 0.9f, 0.9f);

    private final ChartLabelModel model;

    private final String metricName;

    private final transient StatsBuffered statsBuffered;

    /** is this all of history (as opposed to only recent)? */
    private final boolean allHistory;

    /** the number of labels to generate */
    private long numSteps;

    int stringHeight;
    int stringHeightNoDesc;
    int minXs;
    int maxXs;
    int minYs;
    int maxYs;
    double minXb;
    double maxXb;
    double minYb;
    double maxYb;

    /**
     * A chart that displays the history of a statistic from a StatsBuffer
     *
     * @param width         width of the panel in pixels
     * @param height        height of the panel in pixels
     * @param allHistory    is this all of history (as opposed to only recent)?
     * @param statsBuffered {@link StatsBuffered} of this chart
     * @param metricName    metric name to display in the chart title
     */
    public Chart(
            @NonNull ChartLabelModel model,
            int width,
            int height,
            boolean allHistory,
            StatsBuffered statsBuffered,
            String metricName) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.setPreferredSize(new Dimension(width, height));
        this.allHistory = allHistory;
        this.metricName = metricName;
        this.statsBuffered = statsBuffered;
        this.setBackground(Color.WHITE);
    }

    /**
     * translate an x value from the buffer to screen coordinates, clipping it to lie inside the chart.
     *
     * @param x the x parameter from the buffer
     * @return the x screen coordinate
     */
    private int scaleX(double x) {
        if (minXb == maxXb) { // if only one data point,
            return (minXs + maxXs) / 2; // then display it in the middle
        }
        return (int) (Math.min(1, Math.max(0, (x - minXb) / (maxXb - minXb))) * (maxXs - minXs) + minXs);
    }

    /**
     * translate a y value from the buffer to screen coordinates, clipping it to lie inside the chart.
     *
     * @param y the y parameter from the buffer
     * @return the y screen coordinate
     */
    private int scaleY(double y) {
        if (minYb == maxYb) { // if a value has been constant throughout history,
            return (minYs + maxYs) / 2; // then display it in the middle
        }
        return (int) (maxYs - Math.min(1, Math.max(0, (y - minYb) / (maxYb - minYb))) * (maxYs - minYs));
    }

    /**
     * all x axis labels reflect x values divided by this. This is 1 for seconds, 60 for minutes, etc.
     */
    double xDivisor = 1;
    /**
     * the units for the x axis labels. If "seconds", then xDivisor is 1, if "minutes", then 60, etc.
     */
    String xUnits = "seconds";

    /**
     * Check if "step" is a good step size. If so, set numSteps to the number of labels it will generate, and return
     * true. Else return false.
     *
     * @param step the proposed step size
     * @param min  the minimum value of all the data
     * @param max  the maximum value of all the data
     * @return true if this step size won't produce too many labels
     */
    private boolean good(double step, double min, double max) {
        if (step <= 0 || max < min) {
            return false;
        }
        numSteps = 1 + Math.round(Math.ceil(max / step) - Math.floor(min / step));
        return 4 <= numSteps && numSteps <= 7;
    }

    /**
     * Create the y labels and choose their positions
     * <p>
     * This method "returns" xLabel and xLabelVal, by changing those class variables.
     */
    private void findYLabel() {
        if (maxYb == minYb) {
            String s = String.format("%,.0f", minYb);
            model.setYLabels(new String[] {s});
            model.setYLabelValues(new double[] {minYb});
            return;
        }
        double step10 = Math.pow(10, Math.floor(Math.log10(maxYb - minYb)) - 1);
        double step = 1;
        for (int i = 0; i < 4; i++) { // find a step that results in a good number of labels
            if (good(step = step10, minYb, maxYb)) {
                break;
            }
            if (good(step = step10 * 2, minYb, maxYb)) {
                break;
            }
            if (good(step = step10 * 5, minYb, maxYb)) {
                break;
            }
            step10 *= 10;
        }
        // number of decimal places to show, for the given step (0 if showing an integer is ok)
        long decimals = Math.round(Math.max(0, -Math.floor(Math.log10(step))));
        double epsilon = Math.pow(10, -1 - decimals); // if showing 2 decimal places, this is 0.001
        double localMaxYb = Math.ceil(maxYb / step) * step + epsilon;
        final String[] yLabel = new String[(int) numSteps - 2];
        final double[] yLabelVal = new double[(int) numSteps - 2];
        for (int i = 0; i < numSteps - 2; i++) {
            yLabelVal[i] = localMaxYb - (i + 1) * step + epsilon;
            yLabel[i] = String.format("%,." + decimals + "f", yLabelVal[i]);
        }
        model.setYLabels(yLabel);
        model.setYLabelValues(yLabelVal);
    }

    /**
     * Create the x labels and choose their positions
     * <p>
     * This method "returns" xLabel and xLabelVal, by changing those class variables.
     */
    private void findXLabel() {
        if (maxXb <= minXb) {
            String s = String.format("%,.0f", minXb);
            model.setXLabels(new String[] {s});
            model.setXLabelValues(new double[] {minXb});
            return;
        }
        double step10 = Math.pow(10, Math.floor(Math.log10(maxXb - minXb)) - 1);
        double step = 1;
        for (int i = 0; i < 4; i++) { // find a step that results in a good number of labels
            if (good(step = step10, minXb, maxXb)) {
                break;
            }
            if (good(step = step10 * 2, minXb, maxXb)) {
                break;
            }
            if (good(step = step10 * 5, minXb, maxXb)) {
                break;
            }
            step10 *= 10;
        }
        if (!good(step, minXb, maxXb)) {
            // couldn't find a good step size. Maybe numSteps or the data are NaN?
            numSteps = 4;
            step = 1;
        }
        // number of decimal places to show, for the given step (0 if showing an integer is ok)
        long decimals = Math.round(Math.max(0, -Math.floor(Math.log10(step))));
        double epsilon = Math.pow(10, -1 - decimals); // if showing 2 decimal places, this is 0.001
        double localMaxXb = Math.ceil(maxXb / step) * step + epsilon;
        final String[] xLabel = new String[(int) numSteps - 2];
        final double[] xLabelVal = new double[(int) numSteps - 2];
        for (int i = 0; i < numSteps - 2; i++) {
            xLabelVal[i] = localMaxXb - (i + 1) * step + epsilon;
            xLabel[i] = String.format("%,." + decimals + "f", xLabelVal[i]);
        }
        model.setXLabels(xLabel);
        model.setXLabelValues(xLabelVal);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        try {
            StatsBuffer buffer;
            if (allHistory) {
                buffer = statsBuffered.getAllHistory();
            } else {
                buffer = statsBuffered.getRecentHistory();
            }

            minXs = 120;
            maxXs = getWidth() - 55;
            minYs = 40;
            maxYs = getHeight() - 50;

            minXb = buffer.xMin();
            maxXb = buffer.xMax();
            minYb = buffer.yMin();
            maxYb = buffer.yMax();

            if (maxXb - minXb > 60 * 60 * 24 * 365) {
                xDivisor = 60 * 60 * 24 * 365;
                xUnits = "years";
            } else if (maxXb - minXb > 60 * 60 * 24) {
                xDivisor = 60 * 60 * 24;
                xUnits = "days";
            } else if (maxXb - minXb > 60 * 60) {
                xDivisor = 60 * 60;
                xUnits = "hours";
            } else if (maxXb - minXb > 60 * 1.1) {
                xDivisor = 60;
                xUnits = "minutes";
            } else {
                xDivisor = 1;
                xUnits = "seconds";
            }

            g.setColor(Color.BLACK);
            stringHeight = g.getFontMetrics().getHeight();
            stringHeightNoDesc =
                    g.getFontMetrics().getHeight() - g.getFontMetrics().getDescent();
            String title = metricName + " vs. time for " + (allHistory ? "all" : "recent") + " history";

            if (buffer.numBins() == 0) {
                final String s = "Skipping the first 60 seconds ...";
                final int w = g.getFontMetrics().stringWidth(s);
                g.drawString(s, (minXs + maxXs - w) / 2, (minYs + maxYs) / 2);
                g.drawLine(minXs, maxYs, maxXs, maxYs); // x axis
                g.drawLine(minXs, minYs, minXs, maxYs); // y axis
            }
            for (int i = 0; i < buffer.numBins(); i++) {
                g.setColor(LIGHT_GRAY);
                int x0, y0, x1, y1;
                x0 = scaleX(i == 0 ? buffer.xMin(i) : buffer.xMax(i - 1)); // eliminate gaps
                y0 = scaleY(buffer.yMax(i));
                x1 = scaleX(buffer.xMax(i));
                y1 = scaleY(buffer.yMin(i));
                g.fillRect(x0, y0, x1 - x0, y1 - y0);
                g.setColor(DARK_GRAY);
                x0 = scaleX(i == 0 ? buffer.xMin(i) : buffer.xMax(i - 1)); // eliminate gaps
                y0 = scaleY(buffer.yAvg(i) + buffer.yStd(i));
                x1 = scaleX(buffer.xMax(i));
                y1 = scaleY(buffer.yAvg(i) - buffer.yStd(i));
                g.fillRect(x0, y0, x1 - x0, y1 - y0);
                g.setColor(Color.BLUE);
                if (i > 0) {
                    x0 = scaleX(buffer.xAvg(i - 1));
                    y0 = scaleY(buffer.yAvg(i - 1));
                    x1 = scaleX(buffer.xAvg(i));
                    y1 = scaleY(buffer.yAvg(i));
                    g.drawLine(x0, y0, x1, y1);
                }
            }
            g.setColor(Color.BLACK);

            // draw title and x axis name:

            int w = g.getFontMetrics().stringWidth(title);
            g.drawString(title, minXs + (maxXs - minXs - w) / 2, stringHeight);
            String s = "Time in the past (in " + xUnits + ")";
            w = g.getFontMetrics().stringWidth(s);
            g.drawString(s, minXs + (maxXs - minXs - w) / 2, getHeight() - stringHeight);

            if (buffer.numBins() == 0) {
                return; // don't draw labels when there's no data
            }

            // draw X axis labels:

            double tt = minXb;
            minXb = (minXb - tt) / xDivisor;
            maxXb = (maxXb - tt) / xDivisor;
            double t2 = (maxXb - minXb) / 50;
            minXb -= t2; // make sure the 0 label is displayed
            findXLabel(); // labels reflect "time ago", scaled to current units
            minXb += t2;
            minXb = minXb * xDivisor + tt;
            maxXb = maxXb * xDivisor + tt;

            final double[] xLabelVal = new double[model.getXLabelValues().length];
            for (int i = 0; i < model.getXLabelValues().length; i++) {
                xLabelVal[i] = model.getXLabelValues()[i] * xDivisor + tt;
            }
            model.setXLabelValues(xLabelVal);

            for (int i = 0; i < model.getXLabels().length; i++) {
                int x = maxXs - (scaleX(model.getXLabelValues()[i]) - minXs);
                int stringWidth = g.getFontMetrics().stringWidth(model.getXLabels()[i]);
                g.drawLine(x, maxYs + 4, x, maxYs - 4);
                g.drawString(model.getXLabels()[i], x - stringWidth / 2 + 1, maxYs + stringHeight + 5);
            }

            // draw Y axis labels:

            findYLabel();

            for (int i = 0; i < model.getYLabels().length; i++) {
                int y = scaleY(model.getYLabelValues()[i]);
                int stringWidth = g.getFontMetrics().stringWidth(model.getYLabels()[i]);
                g.drawLine(minXs - 4, y, minXs + 4, y);
                g.drawString(model.getYLabels()[i], minXs - stringWidth - 10, y + stringHeightNoDesc / 2 - 1);
            }

            // draw X and Y axes

            g.drawLine(minXs, maxYs, maxXs, maxYs); // x axis
            g.drawLine(minXs, minYs, minXs, maxYs); // y axis
        } catch (Exception e) {
            logger.error(EXCEPTION.getMarker(), "error while painting: {}", e);
        }
    }
}
