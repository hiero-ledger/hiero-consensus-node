// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
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
            names = {"-s", "--separate-files"},
            description = "Write metrics into different files.")
    private boolean separateFiles;

    @Option(
            names = {"-p", "--pattern"},
            defaultValue = "MainNetStats*.csv",
            description = "Glob pattern for CSV files in statsDir (default: ${DEFAULT-VALUE}).")
    private String filePattern;

    @Option(
            names = {"-t", "--force-tables"},
            description = "Force generation of data tables even for large datasets.")
    private boolean forceTables;

    public static void main(final String[] args) {
        final int exitCode = new CommandLine(new StatsCharter()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        final Map<String, Path> csvFiles = findCsvFiles(rootDir, filePattern);
        if (csvFiles.isEmpty()) {
            System.err.println("No files matching '" + filePattern + "' found under " + rootDir);
            return 1;
        }

        System.out.printf("%n  Root:  %s%n", rootDir);
        System.out.printf("  Files: %d matching '%s'%n", csvFiles.size(), filePattern);
        for (final Entry<String, Path> f : csvFiles.entrySet()) {
            System.out.printf("    %s:%s%n", f.getKey(), rootDir.relativize(f.getValue()));
        }

        final List<ParsableMetric> allMetrics = StatsFileParser.availableMetrics(csvFiles);
        if (allMetrics.isEmpty()) {
            System.err.println("No metrics found");
            return 1;
        }

        // Filter to requested metrics, or use all
        final List<ParsableMetric> selected;
        if (metricNames != null && !metricNames.isEmpty()) {
            final Set<String> requested = Set.copyOf(metricNames);
            selected = allMetrics.stream()
                    .filter(m -> requested.contains(m.name()))
                    .toList();
            // Report any names that weren't found
            final Set<String> found =
                    selected.stream().map(ParsableMetric::name).collect(Collectors.toSet());
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

        ChartGenerator.generateMultiMetric(selected, csvFiles, rootDir, forceTables, separateFiles);

        return 0;
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

    @NonNull
    private static Map<String, Path> findCsvFiles(@NonNull final Path root, @NonNull final String pattern)
            throws IOException {
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .collect(Collectors.toMap(p -> nodeNameFromFile(p, pattern), Function.identity()));
        }
    }
}
