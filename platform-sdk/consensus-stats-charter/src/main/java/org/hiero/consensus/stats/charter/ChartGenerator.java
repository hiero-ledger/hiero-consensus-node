// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.pdf.PDFDocument;
import org.jfree.pdf.PDFGraphics2D;
import org.jfree.pdf.Page;

/**
 * Generates PDF charts from metric series using JFreeChart and JFreePDF.
 * Each metric gets a chart and a data table. For ≤10 nodes they share a page
 * (chart left, table right). For >10 nodes they use separate pages.
 */
public final class ChartGenerator {

    /** 50 visually distinct colors generated via HSB, with the constant 6 first. */
    private static final Color[] NODE_COLORS = generateColors();

    private static final int CHART_ONLY_WIDTH = 600;
    private static final int CHART_ONLY_HEIGHT = 400;

    private static final int ROW_HEIGHT = 13;
    private static final Font TABLE_FONT = new Font("Monospaced", Font.PLAIN, 9);
    private static final Font TABLE_HEADER_FONT = new Font("Monospaced", Font.BOLD, 9);
    private static final Color SHADE_OF_WHITE = new Color(200, 200, 200);
    private static final Color ANOTHER_SHADE_OF_WHITE = new Color(245, 245, 245);
    private static final Color YET_ANOTHER_SHADE_OF_WHITE = new Color(252, 252, 252);
    private static final int MAX_COLORS = 50;

    private ChartGenerator() {}

    /**
     * Creates a multi-metric PDF with pages per metric (sorted by name) and a page with table values if indicated.
     */
    public static void generateMultiMetric(
            @NonNull final List<ParsableMetric> metrics,
            @NonNull final Map<String, Path> csvFiles,
            @NonNull final Path pdfPath,
            final boolean addValueTable,
            final boolean separateFiles) {
        final List<ParsableMetric> sortedMetrics = new ArrayList<>(metrics);
        sortedMetrics.sort(Comparator.comparing(p -> p.name().toLowerCase()));

        final PDFDocument global = !separateFiles ? new PDFDocument() : null;
        final Supplier<PDFDocument> docSupplier = () -> separateFiles ? new PDFDocument() : global;
        final Function<ParsableMetric, String> nameSupplier = metric -> separateFiles ? metric.name() : "metrics";
        final Stream<ParsableMetric> stream =
                // speedup in case we can print multiple files per metric, we can use multithreading
                separateFiles ? sortedMetrics.stream().parallel() : sortedMetrics.stream();
        stream.forEach(metric -> {
            try {
                final PDFDocument doc = docSupplier.get();
                final List<ParsedSeries> seriesList = new ArrayList<>();
                for (final Map.Entry<String, Path> entry : csvFiles.entrySet()) {
                    final String nodeName = entry.getKey();
                    final Path csvFile = entry.getValue();
                    final Integer column = metric.seriesLocationPerFile().get(nodeName);
                    if (column != null) {
                        final List<Double> values = StatsFileParser.parseColumn(csvFile, column);
                        seriesList.add(new ParsedSeries(nodeName, values));
                    }
                }
                if (!seriesList.isEmpty()) {
                    addChartPage(doc, seriesList, metric.name(), metric.description());
                    if (addValueTable) {
                        addTablePage(doc, seriesList, metric.name());
                    }
                }
                Files.write(pdfPath.resolve(nameSupplier.apply(metric)), doc.getPDFBytes());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void addChartPage(
            @NonNull final PDFDocument doc,
            @NonNull final List<ParsedSeries> seriesList,
            @NonNull final String title,
            @NonNull final String subtitle) {
        final XYSeriesCollection dataset = buildDataset(seriesList);
        final JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Step", "", dataset, PlotOrientation.VERTICAL, true, false, false);

        if (!subtitle.isEmpty()) {
            final TextTitle sub = new TextTitle(subtitle, new Font("SansSerif", Font.ITALIC, 10));
            sub.setPaint(Color.DARK_GRAY);
            chart.addSubtitle(sub);
        }

        final XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(SHADE_OF_WHITE);
        plot.setRangeGridlinePaint(SHADE_OF_WHITE);

        final XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);
        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            final Color c = NODE_COLORS[i % NODE_COLORS.length];
            renderer.setSeriesPaint(i, c);
            renderer.setSeriesStroke(i, new BasicStroke(1.2f));
            renderer.setSeriesShapesVisible(i, true);
            renderer.setSeriesShape(i, new java.awt.geom.Ellipse2D.Double(-2, -2, 4, 4));
        }
        plot.setRenderer(renderer);

        final Page page = doc.createPage(new Rectangle(CHART_ONLY_WIDTH, CHART_ONLY_HEIGHT));
        final PDFGraphics2D g2 = page.getGraphics2D();
        chart.draw(g2, new Rectangle(CHART_ONLY_WIDTH, CHART_ONLY_HEIGHT));
    }

    private static void addTablePage(
            @NonNull final PDFDocument doc, @NonNull final List<ParsedSeries> seriesList, @NonNull final String title) {
        final int nodeCount = seriesList.size();
        final int maxLen = maxSteps(seriesList);
        final int pageHeight = (maxLen + 4) * ROW_HEIGHT + 30;
        final int nodeColWidth = 70;
        final int pageWidth = 40 + nodeCount * nodeColWidth + 40;

        final Page page = doc.createPage(new Rectangle(pageWidth, pageHeight));
        final PDFGraphics2D g2 = page.getGraphics2D();

        // Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.setColor(Color.DARK_GRAY);
        final int x = 10;
        final int y = 12;
        g2.drawString(title, x, y);

        final int rowHeight = ROW_HEIGHT;
        final int stepColWidth = 30;
        final int tableX = x + 10;
        int rowY = y + rowHeight;

        // Background
        g2.setColor(YET_ANOTHER_SHADE_OF_WHITE);
        g2.fillRect(x, y, pageWidth, pageHeight);

        // Header
        g2.setFont(TABLE_HEADER_FONT);
        g2.setColor(Color.DARK_GRAY);
        drawRightAligned(g2, "Step", tableX, rowY, stepColWidth);
        for (int n = 0; n < nodeCount; n++) {
            g2.setColor(NODE_COLORS[n % NODE_COLORS.length]);
            drawRightAligned(
                    g2, seriesList.get(n).nodeName(), tableX + stepColWidth + n * nodeColWidth, rowY, nodeColWidth);
        }

        // Separator
        rowY += rowHeight;
        g2.setColor(SHADE_OF_WHITE);
        g2.drawLine(tableX, rowY - 3, tableX + stepColWidth + nodeCount * nodeColWidth, rowY - 3);

        // Data
        g2.setFont(TABLE_FONT);
        final int maxY = y + pageHeight;

        for (int i = 0; i < maxLen; i++) {
            rowY += rowHeight;
            if (rowY > maxY - 5) {
                break;
            }

            // Alternating row background
            if (i % 2 == 0) {
                g2.setColor(ANOTHER_SHADE_OF_WHITE);
                g2.fillRect(tableX, rowY - rowHeight + 3, stepColWidth + nodeCount * nodeColWidth, rowHeight);
            }

            // Step number
            g2.setColor(Color.GRAY);
            drawRightAligned(g2, String.valueOf(i), tableX, rowY, stepColWidth);

            // Values
            for (int n = 0; n < nodeCount; n++) {
                final List<Double> vals = seriesList.get(n).values();
                if (i < vals.size() && vals.get(i) != null) {
                    g2.setColor(NODE_COLORS[n % NODE_COLORS.length].darker());
                    drawRightAligned(
                            g2, formatValue(vals.get(i)), tableX + stepColWidth + n * nodeColWidth, rowY, nodeColWidth);
                }
            }
        }
    }

    @NonNull
    private static XYSeriesCollection buildDataset(@NonNull final List<ParsedSeries> seriesList) {
        final XYSeriesCollection dataset = new XYSeriesCollection();
        for (final ParsedSeries ms : seriesList) {
            final XYSeries xySeries = new XYSeries(ms.nodeName());
            final List<Double> vals = ms.values();
            for (int i = 0; i < vals.size(); i++) {
                final Double v = vals.get(i);
                if (v != null) {
                    xySeries.add(i, v);
                }
            }
            dataset.addSeries(xySeries);
        }
        return dataset;
    }

    private static void drawRightAligned(
            @NonNull final Graphics2D g2, @NonNull final String text, final int x, final int y, final int colWidth) {
        final int textWidth = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, x + colWidth - textWidth - 4, y);
    }

    @NonNull
    private static String formatValue(final double v) {
        if (v == 0.0) {
            return "0";
        }
        if (Math.abs(v) >= 100) {
            return String.format("%.1f", v);
        }
        if (Math.abs(v) >= 1) {
            return String.format("%.3f", v);
        }
        return String.format("%.6f", v);
    }

    /**
     * Deterministic Color generation
     * @return an array of 50 colors
     */
    @NonNull
    private static Color[] generateColors() {
        // Start with 6 hand-picked colors for small node counts
        final Color[] base = {
            new Color(0xe7, 0x4c, 0x3c),
            new Color(0x2e, 0xcc, 0x71),
            new Color(0x34, 0x98, 0xdb),
            new Color(0xf3, 0x9c, 0x12),
            new Color(0x9b, 0x59, 0xb6),
            new Color(183, 188, 26),
        };
        final Color[] colors = new Color[MAX_COLORS];
        System.arraycopy(base, 0, colors, 0, base.length);
        // Generate remaining via evenly-spaced HSB hues
        for (int i = base.length; i < MAX_COLORS; i++) {
            final float hue = (i * 0.618034f) % 1.0f; // golden ratio spacing
            final float sat = 0.65f + (i % 3) * 0.1f;
            final float bri = 0.75f + (i % 2) * 0.15f;
            colors[i] = Color.getHSBColor(hue, sat, bri);
        }
        return colors;
    }

    private static int maxSteps(@NonNull final List<ParsedSeries> seriesList) {
        int maxLen = 0;
        for (final ParsedSeries ms : seriesList) {
            maxLen = Math.max(maxLen, ms.values().size());
        }
        return maxLen;
    }
}
