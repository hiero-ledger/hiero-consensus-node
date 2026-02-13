// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final Color[] NODE_COLORS = generateColors(50);

    private static final int SIDE_BY_SIDE_THRESHOLD = 10;

    // Side-by-side layout (≤10 nodes)
    private static final int CHART_WIDTH = 700;
    private static final int TABLE_WIDTH = 500;
    private static final int PAGE_WIDTH_COMBINED = CHART_WIDTH + TABLE_WIDTH;

    // Separate-page layout (>10 nodes)
    private static final int CHART_ONLY_WIDTH = 1000;
    private static final int CHART_ONLY_HEIGHT = 500;

    private static final int ROW_HEIGHT = 13;
    private static final Font TABLE_FONT = new Font("Monospaced", Font.PLAIN, 9);
    private static final Font TABLE_HEADER_FONT = new Font("Monospaced", Font.BOLD, 9);
    private static final Font TABLE_FONT_SMALL = new Font("Monospaced", Font.PLAIN, 7);
    private static final Font TABLE_HEADER_FONT_SMALL = new Font("Monospaced", Font.BOLD, 7);
    private static final int ROW_HEIGHT_SMALL = 10;

    // Index page layout
    private static final int INDEX_PAGE_WIDTH = 800;
    private static final int INDEX_PAGE_HEIGHT = 600;
    private static final int INDEX_MARGIN = 30;
    private static final int INDEX_ROW_HEIGHT = 14;
    private static final Font INDEX_TITLE_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font INDEX_METRIC_FONT = new Font("Monospaced", Font.BOLD, 9);
    private static final Font INDEX_DESC_FONT = new Font("SansSerif", Font.PLAIN, 8);

    private ChartGenerator() {}

    /** Tracks an index row's position for link injection. */
    private record IndexEntry(int indexPageObjNum, @NonNull String metricName, float rowY) {}

    /**
     * Creates a multi-metric PDF with pages per metric (sorted by name).
     * Index pages at the front contain clickable links to each metric's chart page.
     */
    public static void generateMultiMetric(
            @NonNull final Map<ParsedMetric, List<ParsedSeries>> metricData,
            @NonNull final Path pdfPath,
            final boolean clipOutliers)
            throws IOException {
        final List<ParsedMetric> sortedMetrics = new ArrayList<>(metricData.keySet());
        sortedMetrics.sort(Comparator.comparing(p->p.name().toLowerCase()));

        final PDFDocument doc = new PDFDocument();

        // 1. Create index pages and record each entry's position
        final List<IndexEntry> indexEntries = addIndexPages(doc, sortedMetrics);

        // 2. Create metric pages and record each metric's first page reference
        final Map<String, String> metricPageRefs = new HashMap<>();
        for (final ParsedMetric metric : sortedMetrics) {
            final List<ParsedSeries> seriesList = metricData.get(metric);
            final List<Double> allVals = collectValues(seriesList);
            if (allVals.isEmpty()) {
                continue;
            }
            Collections.sort(allVals);
            final XYSeriesCollection dataset = buildDataset(seriesList);

            final Page firstPage = addMetricPages(
                    doc, dataset, seriesList, metric.name(), metric.description(), allVals, clipOutliers);
            metricPageRefs.put(metric.name(), firstPage.getReference());
        }

        // 3. Build link annotations for the index
        final List<PdfLinkInjector.Link> links = new ArrayList<>();
        for (final IndexEntry entry : indexEntries) {
            final String ref = metricPageRefs.get(entry.metricName());
            if (ref != null) {
                links.add(new PdfLinkInjector.Link(
                        entry.indexPageObjNum(),
                        INDEX_MARGIN,
                        entry.rowY() - INDEX_ROW_HEIGHT + 2,
                        INDEX_PAGE_WIDTH - INDEX_MARGIN,
                        entry.rowY() + 2,
                        ref,
                        INDEX_PAGE_HEIGHT));
            }
        }

        // 4. Get raw PDF bytes, inject links, and write
        final byte[] pdfBytes = doc.getPDFBytes();
        final byte[] withLinks = PdfLinkInjector.inject(pdfBytes, links);
        Files.write(pdfPath, withLinks);

        System.out.println("  PDF chart saved to: " + pdfPath + " (" + sortedMetrics.size() + " metrics)");
    }

    // --- Index pages ---

    @NonNull
    private static List<IndexEntry> addIndexPages(
            @NonNull final PDFDocument doc, @NonNull final List<ParsedMetric> metrics) {
        final List<IndexEntry> entries = new ArrayList<>();
        final int usableHeight = INDEX_PAGE_HEIGHT - INDEX_MARGIN * 2;
        final int titleSpace = 30;
        final int rowsFirstPage = (usableHeight - titleSpace) / INDEX_ROW_HEIGHT;
        final int rowsPerPage = usableHeight / INDEX_ROW_HEIGHT;

        int metricIdx = 0;
        int pageNum = 0;
        while (metricIdx < metrics.size()) {
            final int rowsThisPage = (pageNum == 0) ? rowsFirstPage : rowsPerPage;
            final Page page = doc.createPage(new Rectangle(INDEX_PAGE_WIDTH, INDEX_PAGE_HEIGHT));
            final PDFGraphics2D g2 = page.getGraphics2D();
            final int pageObjNum = page.getNumber();

            int y = INDEX_MARGIN;

            if (pageNum == 0) {
                g2.setFont(INDEX_TITLE_FONT);
                g2.setColor(Color.DARK_GRAY);
                g2.drawString("Metric Index  (" + metrics.size() + " metrics)", INDEX_MARGIN, y + 14);
                y += titleSpace;
            }

            // Column header
            g2.setFont(INDEX_METRIC_FONT);
            g2.setColor(new Color(100, 100, 100));
            g2.drawString("#", INDEX_MARGIN, y + 10);
            g2.drawString("Metric", INDEX_MARGIN + 30, y + 10);
            g2.setFont(INDEX_DESC_FONT);
            g2.drawString("Description", INDEX_MARGIN + 280, y + 10);
            y += INDEX_ROW_HEIGHT;
            g2.setColor(new Color(200, 200, 200));
            g2.drawLine(INDEX_MARGIN, y, INDEX_PAGE_WIDTH - INDEX_MARGIN, y);
            y += 2;

            int rowsDrawn = 0;
            while (metricIdx < metrics.size() && rowsDrawn < rowsThisPage - 1) {
                final ParsedMetric metric = metrics.get(metricIdx);

                // Alternating background
                if (metricIdx % 2 == 0) {
                    g2.setColor(new Color(245, 247, 250));
                    g2.fillRect(INDEX_MARGIN, y, INDEX_PAGE_WIDTH - INDEX_MARGIN * 2, INDEX_ROW_HEIGHT);
                }

                // Row number
                g2.setFont(INDEX_DESC_FONT);
                g2.setColor(Color.GRAY);
                g2.drawString(String.valueOf(metricIdx + 1), INDEX_MARGIN + 2, y + 10);

                // Metric name (bold monospace, blue to hint clickability)
                g2.setFont(INDEX_METRIC_FONT);
                g2.setColor(new Color(0x1a, 0x56, 0xdb));
                g2.drawString(metric.name(), INDEX_MARGIN + 30, y + 10);

                // Description (truncated if too long)
                if (!metric.description().isEmpty()) {
                    g2.setFont(INDEX_DESC_FONT);
                    g2.setColor(new Color(80, 80, 80));
                    final String truncated =
                            truncateToFit(g2, metric.description(), INDEX_PAGE_WIDTH - INDEX_MARGIN - 280 - 10);
                    g2.drawString(truncated, INDEX_MARGIN + 280, y + 10);
                }

                entries.add(new IndexEntry(pageObjNum, metric.name(), y + INDEX_ROW_HEIGHT));

                y += INDEX_ROW_HEIGHT;
                metricIdx++;
                rowsDrawn++;
            }

            // Page number footer
            g2.setFont(INDEX_DESC_FONT);
            g2.setColor(Color.GRAY);
            final String pageLabel = "Page " + (pageNum + 1);
            final FontMetrics fm = g2.getFontMetrics();
            g2.drawString(
                    pageLabel, INDEX_PAGE_WIDTH - INDEX_MARGIN - fm.stringWidth(pageLabel), INDEX_PAGE_HEIGHT - 10);

            pageNum++;
        }
        return entries;
    }

    @NonNull
    private static String truncateToFit(
            @NonNull final Graphics2D g2, @NonNull final String text, final int maxWidth) {
        final FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        final String ellipsis = "...";
        final int ellipsisWidth = fm.stringWidth(ellipsis);
        for (int i = text.length() - 1; i > 0; i--) {
            if (fm.stringWidth(text.substring(0, i)) + ellipsisWidth <= maxWidth) {
                return text.substring(0, i) + ellipsis;
            }
        }
        return ellipsis;
    }

    // --- Page builders ---

    @NonNull
    private static Page addMetricPages(
            @NonNull final PDFDocument doc,
            @NonNull final XYSeriesCollection dataset,
            @NonNull final List<ParsedSeries> seriesList,
            @NonNull final String title,
            @NonNull final String subtitle,
            @NonNull final List<Double> sortedVals,
            final boolean clipOutliers) {
        String chartTitle = title;
        if (clipOutliers && shouldClip(sortedVals)) {
            chartTitle = title + "  (clipped)";
        }
        final JFreeChart chart = createChart(dataset, chartTitle, subtitle);

        if (clipOutliers && shouldClip(sortedVals)) {
            final double p2 = percentile(sortedVals, 0.02);
            final double p98 = percentile(sortedVals, 0.98);
            final double margin = (p98 - p2) * 0.1;
            chart.getXYPlot().getRangeAxis().setRange(Math.max(0, p2 - margin), p98 + margin);
        }

        if (seriesList.size() <= SIDE_BY_SIDE_THRESHOLD) {
            final int tableHeight = computeTableHeight(seriesList);
            final int pageHeight = Math.max(400, tableHeight + 30);
            final Page page = doc.createPage(new Rectangle(PAGE_WIDTH_COMBINED, pageHeight));
            final PDFGraphics2D g2 = page.getGraphics2D();
            chart.draw(g2, new Rectangle(0, 0, CHART_WIDTH, pageHeight));
            drawTable(g2, seriesList, CHART_WIDTH, 0, TABLE_WIDTH, pageHeight);
            return page;
        } else {
            final Page firstPage = addChartOnlyPage(doc, chart);
            addTableOnlyPage(doc, seriesList, title);
            return firstPage;
        }
    }

    @NonNull
    private static Page addChartOnlyPage(@NonNull final PDFDocument doc, @NonNull final JFreeChart chart) {
        final Page page = doc.createPage(new Rectangle(CHART_ONLY_WIDTH, CHART_ONLY_HEIGHT));
        final PDFGraphics2D g2 = page.getGraphics2D();
        chart.draw(g2, new Rectangle(CHART_ONLY_WIDTH, CHART_ONLY_HEIGHT));
        return page;
    }

    private static void addTableOnlyPage(
            @NonNull final PDFDocument doc,
            @NonNull final List<ParsedSeries> seriesList,
            @NonNull final String title) {
        final int nodeCount = seriesList.size();
        final boolean small = nodeCount > 20;
        final int rh = small ? ROW_HEIGHT_SMALL : ROW_HEIGHT;
        final int maxLen = maxSteps(seriesList);
        final int pageHeight = (maxLen + 4) * rh + 30;
        final int nodeColWidth = small ? 55 : 70;
        final int pageWidth = 40 + nodeCount * nodeColWidth + 40;

        final Page page = doc.createPage(new Rectangle(pageWidth, pageHeight));
        final PDFGraphics2D g2 = page.getGraphics2D();

        // Title
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.setColor(Color.DARK_GRAY);
        g2.drawString(title, 10, 12);

        drawTableFull(g2, seriesList, 0, 16, pageWidth, pageHeight - 16, small);
    }

    // --- Data table rendering (side-by-side, ≤10 nodes) ---

    private static int computeTableHeight(@NonNull final List<ParsedSeries> seriesList) {
        return (maxSteps(seriesList) + 3) * ROW_HEIGHT + 10;
    }

    private static void drawTable(
            @NonNull final Graphics2D g2,
            @NonNull final List<ParsedSeries> seriesList,
            final int x,
            final int y,
            final int width,
            final int pageHeight) {
        final int maxLen = maxSteps(seriesList);
        final int nodeCount = seriesList.size();
        final int stepColWidth = 35;
        final int nodeColWidth = nodeCount == 0 ? 0 : Math.min((width - 40) / (nodeCount + 1), 90);
        final int tableX = x + 10;
        int rowY = y + 15;

        // Light background
        g2.setColor(new Color(252, 252, 252));
        g2.fillRect(x, y, width, pageHeight);

        // Vertical separator line
        g2.setColor(new Color(220, 220, 220));
        g2.drawLine(x, y, x, pageHeight);

        // Header
        g2.setFont(TABLE_HEADER_FONT);
        g2.setColor(Color.DARK_GRAY);
        drawRightAligned(g2, "Step", tableX, rowY, stepColWidth);
        for (int n = 0; n < nodeCount; n++) {
            g2.setColor(NODE_COLORS[n % NODE_COLORS.length]);
            drawRightAligned(
                    g2, seriesList.get(n).nodeName(), tableX + stepColWidth + n * nodeColWidth, rowY, nodeColWidth);
        }

        // Separator line
        rowY += ROW_HEIGHT;
        g2.setColor(new Color(200, 200, 200));
        g2.drawLine(tableX, rowY - 3, tableX + stepColWidth + nodeCount * nodeColWidth, rowY - 3);

        // Data
        g2.setFont(TABLE_FONT);
        drawDataRows(g2, seriesList, maxLen, tableX, rowY, stepColWidth, nodeColWidth, pageHeight, ROW_HEIGHT);
    }

    // --- Data table rendering (full-page, >10 nodes) ---

    private static void drawTableFull(
            @NonNull final Graphics2D g2,
            @NonNull final List<ParsedSeries> seriesList,
            final int x,
            final int y,
            final int pageWidth,
            final int pageHeight,
            final boolean small) {
        final int maxLen = maxSteps(seriesList);
        final int nodeCount = seriesList.size();
        final int rh = small ? ROW_HEIGHT_SMALL : ROW_HEIGHT;
        final int stepColWidth = 30;
        final int nodeColWidth = small ? 55 : 70;
        final int tableX = x + 10;
        int rowY = y + rh;

        // Background
        g2.setColor(new Color(252, 252, 252));
        g2.fillRect(x, y, pageWidth, pageHeight);

        // Header
        g2.setFont(small ? TABLE_HEADER_FONT_SMALL : TABLE_HEADER_FONT);
        g2.setColor(Color.DARK_GRAY);
        drawRightAligned(g2, "Step", tableX, rowY, stepColWidth);
        for (int n = 0; n < nodeCount; n++) {
            g2.setColor(NODE_COLORS[n % NODE_COLORS.length]);
            drawRightAligned(
                    g2, seriesList.get(n).nodeName(), tableX + stepColWidth + n * nodeColWidth, rowY, nodeColWidth);
        }

        // Separator
        rowY += rh;
        g2.setColor(new Color(200, 200, 200));
        g2.drawLine(tableX, rowY - 3, tableX + stepColWidth + nodeCount * nodeColWidth, rowY - 3);

        // Data
        g2.setFont(small ? TABLE_FONT_SMALL : TABLE_FONT);
        drawDataRows(g2, seriesList, maxLen, tableX, rowY, stepColWidth, nodeColWidth, y + pageHeight, rh);
    }

    // --- Shared data row drawing ---

    private static void drawDataRows(
            @NonNull final Graphics2D g2,
            @NonNull final List<ParsedSeries> seriesList,
            final int maxLen,
            final int tableX,
            final int startY,
            final int stepColWidth,
            final int nodeColWidth,
            final int maxY,
            final int rowHeight) {
        final int nodeCount = seriesList.size();
        int rowY = startY;
        for (int i = 0; i < maxLen; i++) {
            rowY += rowHeight;
            if (rowY > maxY - 5) {
                break;
            }

            // Alternating row background
            if (i % 2 == 0) {
                g2.setColor(new Color(245, 245, 245));
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

    // --- Color generation ---

    @NonNull
    private static Color[] generateColors(final int count) {
        // Start with 6 hand-picked colors for small node counts
        final Color[] base = {
            new Color(0xe7, 0x4c, 0x3c),
            new Color(0x2e, 0xcc, 0x71),
            new Color(0x34, 0x98, 0xdb),
            new Color(0xf3, 0x9c, 0x12),
            new Color(0x9b, 0x59, 0xb6),
            new Color(0x1a, 0xbc, 0x9c),
        };
        final Color[] colors = new Color[count];
        System.arraycopy(base, 0, colors, 0, Math.min(base.length, count));
        // Generate remaining via evenly-spaced HSB hues
        for (int i = base.length; i < count; i++) {
            final float hue = (i * 0.618034f) % 1.0f; // golden ratio spacing
            final float sat = 0.65f + (i % 3) * 0.1f;
            final float bri = 0.75f + (i % 2) * 0.15f;
            colors[i] = Color.getHSBColor(hue, sat, bri);
        }
        return colors;
    }

    // --- Chart + data helpers ---

    private static int maxSteps(@NonNull final List<ParsedSeries> seriesList) {
        int maxLen = 0;
        for (final ParsedSeries ms : seriesList) {
            maxLen = Math.max(maxLen, ms.values().size());
        }
        return maxLen;
    }

    @NonNull
    private static List<Double> collectValues(@NonNull final List<ParsedSeries> seriesList) {
        final List<Double> allVals = new ArrayList<>();
        for (final ParsedSeries s : seriesList) {
            for (final Double v : s.values()) {
                if (v != null) {
                    allVals.add(v);
                }
            }
        }
        return allVals;
    }

    private static boolean shouldClip(@NonNull final List<Double> sortedVals) {
        final double rawMax = sortedVals.getLast();
        final double p98 = percentile(sortedVals, 0.98);
        return p98 > 0 && rawMax / p98 > 3;
    }

    private static double percentile(@NonNull final List<Double> sortedVals, final double pct) {
        final int idx = (int) (sortedVals.size() * pct);
        return sortedVals.get(Math.max(0, Math.min(sortedVals.size() - 1, idx)));
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

    @NonNull
    private static JFreeChart createChart(
            @NonNull final XYSeriesCollection dataset,
            @NonNull final String title,
            @NonNull final String subtitle) {
        final JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Step", "", dataset, PlotOrientation.VERTICAL, true, false, false);

        if (!subtitle.isEmpty()) {
            final TextTitle sub = new TextTitle(subtitle, new Font("SansSerif", Font.ITALIC, 10));
            sub.setPaint(Color.DARK_GRAY);
            chart.addSubtitle(sub);
        }

        final XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(new Color(200, 200, 200));
        plot.setRangeGridlinePaint(new Color(200, 200, 200));

        final XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);
        for (int i = 0; i < dataset.getSeriesCount(); i++) {
            final Color c = NODE_COLORS[i % NODE_COLORS.length];
            renderer.setSeriesPaint(i, c);
            renderer.setSeriesStroke(i, new BasicStroke(1.2f));
            renderer.setSeriesShapesVisible(i, true);
            renderer.setSeriesShape(i, new java.awt.geom.Ellipse2D.Double(-2, -2, 4, 4));
        }
        plot.setRenderer(renderer);

        return chart;
    }
}
