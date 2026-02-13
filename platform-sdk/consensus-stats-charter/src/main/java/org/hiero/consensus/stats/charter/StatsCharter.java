// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI tool that extracts metrics from CSV files and generates PDF charts with embedded data tables.
 *
 * <p>Specify one or more metric names to chart a subset, or omit to chart all metrics.
 */
@Command(
        name = "stats-charter",
        mixinStandardHelpOptions = true,
        description = "Extract metrics from a stats CSV file and generate PDF charts."
                + " Specify one or more metric names, or omit for all metrics.")
public final class StatsCharter implements Callable<Integer> {

    @Parameters(index = "0", description = "Root directory to search (recursively) for CSV files.")
    private Path rootDir;

    @Parameters(
            index = "1..*",
            arity = "0..*",
            description = "Metric column names (e.g. secSC2T secC2C). Omit for all metrics.")
    private List<String> metricNames;

    @Option(
            names = {"-c", "--clip-outliers"},
            description = "Enable outlier clipping (single clipped chart when max > 3x p98).")
    private boolean clipOutliers;

    @Option(
            names = {"-p", "--pattern"},
            defaultValue = "MainNetStats*.csv",
            description = "Glob pattern for CSV files in statsDir (default: ${DEFAULT-VALUE}).")
    private String filePattern;

    public static void main(final String[] args) {
        final int exitCode = new CommandLine(new StatsCharter()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        final List<Path> csvFiles = findCsvFiles(rootDir, filePattern);
        if (csvFiles.isEmpty()) {
            System.err.println("No files matching '" + filePattern + "' found under " + rootDir);
            return 1;
        }

        System.out.printf("%n  Root:  %s%n", rootDir);
        System.out.printf("  Files: %d matching '%s'%n", csvFiles.size(), filePattern);
        for (final Path f : csvFiles) {
            System.out.printf("    %s%n", rootDir.relativize(f));
        }

        final List<ParsedMetric> allMetrics = StatsFileParser.availableMetrics(csvFiles.getFirst());
        if (allMetrics.isEmpty()) {
            System.err.println("No metrics found in " + csvFiles.getFirst().getFileName());
            return 1;
        }

        // Filter to requested metrics, or use all
        final List<ParsedMetric> selected;
        if (metricNames != null && !metricNames.isEmpty()) {
            final Set<String> requested = Set.copyOf(metricNames);
            selected = allMetrics.stream()
                    .filter(m -> requested.contains(m.name()))
                    .toList();
            // Report any names that weren't found
            final Set<String> found =
                    selected.stream().map(ParsedMetric::name).collect(Collectors.toSet());
            for (final String name : metricNames) {
                if (!found.contains(name)) {
                    System.err.println("Warning: metric '" + name + "' not found, skipping.");
                }
            }
            if (selected.isEmpty()) {
                System.err.println("None of the requested metrics were found.");
                return 1;
            }
        } else {
            selected = allMetrics;
        }

        System.out.printf("  Metrics: %d%n%n", selected.size());

        final Map<ParsedMetric, List<ParsedSeries>> metricData = new HashMap<>();
        for (final ParsedMetric metric : selected) {
            final List<ParsedSeries> seriesList = parseMetric(csvFiles, metric);
            if (seriesList.isEmpty()) {
                continue;
            }
            metricData.put(metric, seriesList);
            System.out.printf("  %-40s %d nodes, %d steps%n", metric.name(), seriesList.size(), maxLength(seriesList));
        }

        if (metricData.isEmpty()) {
            System.err.println("No parseable metrics found.");
            return 1;
        }

        final String pdfName = selected.size() == 1
                ? selected.getFirst().name()
                : selected.size() == allMetrics.size() ? "all-metrics" : "subset-metrics";
        final Path pdfPath = rootDir.resolve(pdfName + ".pdf");
        ChartGenerator.generateMultiMetric(metricData, pdfPath, clipOutliers);

        return 0;
    }

    @NonNull
    private List<ParsedSeries> parseMetric(
            @NonNull final List<Path> csvFiles, @NonNull final ParsedMetric info) {
        return csvFiles.stream()
                .map(csvFile -> {
                    try {
                        final List<Double> values = StatsFileParser.parse(csvFile, info);
                        final String nodeName = nodeNameFromFile(csvFile, filePattern);
                        return new ParsedSeries(nodeName, values);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();
    }

    @NonNull
    static String nodeNameFromFile(@NonNull final Path file, @NonNull final String filePattern) {
        final String fileName = file.getFileName().toString();
        // Strip .csv extension
        final String name = fileName.endsWith(".csv") ? fileName.substring(0, fileName.length() - 4) : fileName;
        // Derive prefix from the glob pattern (everything before the first '*')
        String prefix = filePattern;
        final int starIdx = prefix.indexOf('*');
        if (starIdx >= 0) {
            prefix = prefix.substring(0, starIdx);
        }
        // Also strip any extension suffix from the prefix
        if (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (!prefix.isEmpty() && name.startsWith(prefix)) {
            return "Node" + name.substring(prefix.length());
        }
        return name;
    }

    private static int maxLength(@NonNull final List<ParsedSeries> seriesList) {
        int maxLen = 0;
        for (final ParsedSeries ms : seriesList) {
            maxLen = Math.max(maxLen, ms.values().size());
        }
        return maxLen;
    }

    @NonNull
    private static List<Path> findCsvFiles(@NonNull final Path root, @NonNull final String pattern)
            throws IOException {
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .sorted()
                    .toList();
        }
    }
}
