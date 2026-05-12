// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import picocli.CommandLine;

/**
 * Converts Hedera MainNetStats CSV files to Prometheus exposition format.
 *
 * <p>The CSV files produced by the platform have a non-standard layout:
 * <ol>
 *   <li>Description lines (metric_name:, description,) — one per metric</li>
 *   <li>An empty line</li>
 *   <li>A category row (,,Reconnect,app,platform,...)</li>
 *   <li>A metric name header row (,,endsReconnectAsReceiver,DeprTxnsRcv,...)</li>
 *   <li>Data rows (,,0,0,0.000,...)</li>
 * </ol>
 *
 * <p>Some CSV files also include a {@code time} column in the second header row.
 * When present, it is used for timestamps. Otherwise, timestamps are generated
 * from {@code --start-time} plus {@code --interval} per row.
 *
 * <p>Output is Prometheus text exposition format with millisecond timestamps:
 * <pre>metric_name{node="0",run="my_run"} 42.5 1710370800000</pre>
 */
@CommandLine.Command(
        name = "stats-to-prometheus",
        mixinStandardHelpOptions = true,
        description = "Converts stat CSV files to Prometheus exposition format")
@SubcommandOf(Pcli.class)
public class StatsToPrometheusCommand extends AbstractCommand {

    // Matches MainNetStats0.csv, MainNetStats0_1.csv, Stats0.csv, Stats0_1.csv, etc.
    // Group 1 = node ID, Group 2 = optional rotation suffix (_N)
    private static final Pattern CSV_NODE_PATTERN = Pattern.compile("(?:MainNetStats|Stats)(\\d+)(?:_(\\d+))?\\.csv$");

    @CommandLine.Parameters(description = "CSV files or directories containing CSV files")
    private List<Path> inputs;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Output file. If omitted, writes metrics.txt next to each input CSV.")
    private Path output;

    @CommandLine.Option(
            names = {"-n", "--node"},
            description = "Node ID label override (default: extracted from filename)")
    private String nodeId;

    @CommandLine.Option(
            names = {"-r", "--run"},
            description = "Run name label (default: parent directory name)")
    private String runName;

    @CommandLine.Option(
            names = {"-s", "--start-time"},
            description = "Start time as ISO-8601 or epoch seconds. When the CSV has a time column,"
                    + " shifts all timestamps so the first row aligns with this value."
                    + " When there is no time column, used as the base for interval-based timestamps."
                    + " (e.g. 2026-03-14T05:00:00Z or 1710370800)")
    private String startTime;

    @CommandLine.Option(
            names = {"-i", "--interval"},
            description = "Seconds between rows when CSV has no time column (default: 3)",
            defaultValue = "3")
    private int intervalSeconds;

    @CommandLine.Option(
            names = {"-m", "--metrics"},
            description = "Comma-separated metric names to include (default: all)")
    private String metricsFilter;

    @CommandLine.Option(
            names = {"-f", "--metrics-file"},
            description = "File with metric names to include, one per line")
    private Path metricsFile;

    @CommandLine.Option(
            names = {"-l", "--list"},
            description = "List available metric names and exit")
    private boolean listOnly;

    @Override
    public Integer call() throws Exception {
        final List<Path> csvFiles = resolveInputFiles();
        if (csvFiles.isEmpty()) {
            System.err.println("No CSV files found.");
            return 1;
        }

        // In list mode, just list metrics from the first file
        if (listOnly) {
            listMetrics(csvFiles.getFirst());
            return 0;
        }

        final Set<String> filterSet = buildFilterSet();
        final long startEpochSeconds = resolveStartEpoch();

        // Find the earliest timestamp across all CSVs so we can:
        // 1. Compute a single consistent time shift (when --start-time is provided)
        // 2. Pad nodes that started later (e.g. after a restart) with a zero-value entry
        final long earliestTimestampMs = findEarliestTimestamp(csvFiles);
        // The shift is computed once from the global earliest, so all files use the same offset
        final long timeShiftMs;
        if (earliestTimestampMs < Long.MAX_VALUE) {
            System.err.printf("Earliest CSV timestamp: %d ms%n", earliestTimestampMs);
            timeShiftMs = startTime != null ? startEpochSeconds * 1000L - earliestTimestampMs : 0;
        } else {
            timeShiftMs = 0;
        }

        if (output != null) {
            // Single output file for all CSVs
            try (final BufferedWriter writer =
                    new BufferedWriter(new FileWriter(output.toFile(), StandardCharsets.UTF_8))) {
                for (final Path csvFile : csvFiles) {
                    processFile(csvFile, writer, filterSet, startEpochSeconds, timeShiftMs, true);
                }
                writer.write("# EOF\n");
            }
            System.err.println("Written to " + output);
        } else {
            // Group CSVs by parent directory so multiple CSVs in the same node dir
            // all accumulate into a single metrics.txt
            final Map<Path, List<Path>> groups = new LinkedHashMap<>();
            for (final Path csvFile : csvFiles) {
                groups.computeIfAbsent(csvFile.getParent(), k -> new ArrayList<>())
                        .add(csvFile);
            }
            for (final var entry : groups.entrySet()) {
                final Path metricsFile = entry.getKey().resolve("metrics.txt");
                try (final BufferedWriter writer =
                        new BufferedWriter(new FileWriter(metricsFile.toFile(), StandardCharsets.UTF_8))) {
                    boolean paddingDone = false;
                    for (final Path csvFile : entry.getValue()) {
                        processFile(csvFile, writer, filterSet, startEpochSeconds, timeShiftMs, !paddingDone);
                        paddingDone = true;
                    }
                    writer.write("# EOF\n");
                }
                System.err.println("Written to " + metricsFile);
            }
        }

        return 0;
    }

    /**
     * Resolves input paths: directories are scanned for MainNetStats*.csv files,
     * recursively searching inside nodeX/ subdirectories.
     */
    private List<Path> resolveInputFiles() throws IOException {
        final List<Path> result = new ArrayList<>();
        for (final Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (final Stream<Path> walk = Files.walk(input, 3)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                final String name = p.getFileName().toString();
                                if (!name.endsWith(".csv")) {
                                    return false;
                                }
                                final Matcher m = CSV_NODE_PATTERN.matcher(name);
                                return m.matches();
                            })
                            .sorted(Comparator.comparing(
                                    p -> extractNodeNumber(p.getFileName().toString())))
                            .forEach(result::add);
                }
            } else if (Files.isRegularFile(input)) {
                result.add(input);
            } else {
                System.err.println("Warning: skipping " + input + " (not a file or directory)");
            }
        }
        System.err.printf("Found %d CSV file(s)%n", result.size());
        return result;
    }

    private void listMetrics(@NonNull final Path csvFile) throws IOException {
        try (final BufferedReader reader =
                new BufferedReader(new FileReader(csvFile.toFile(), StandardCharsets.UTF_8))) {
            final HeaderInfo headerInfo = findHeaders(reader);
            headerInfo.columns.keySet().stream().sorted().forEach(System.out::println);
        }
    }

    /**
     * @param timeShiftMs  pre-computed shift to apply to all CSV timestamps (0 if no shifting)
     * @param emitPadding  whether to check for and emit a padding row (only for the first file per node)
     */
    private void processFile(
            @NonNull final Path csvFile,
            @NonNull final BufferedWriter writer,
            final Set<String> filterSet,
            final long startEpochSeconds,
            final long timeShiftMs,
            final boolean emitPadding)
            throws IOException {

        final String resolvedNode = resolveNodeId(csvFile);
        final String resolvedRun = resolveRunName(csvFile);

        System.err.println("Processing: " + csvFile + " (node=" + resolvedNode + ")");

        try (final BufferedReader reader =
                new BufferedReader(new FileReader(csvFile.toFile(), StandardCharsets.UTF_8))) {

            final HeaderInfo headerInfo = findHeaders(reader);

            // Determine which columns to export
            final Map<String, Integer> exportCols;
            if (filterSet != null) {
                exportCols = new LinkedHashMap<>();
                for (final var entry : headerInfo.columns.entrySet()) {
                    if (filterSet.contains(entry.getKey())) {
                        exportCols.put(entry.getKey(), entry.getValue());
                    }
                }
                final Set<String> missing = new HashSet<>(filterSet);
                missing.removeAll(exportCols.keySet());
                if (!missing.isEmpty()) {
                    System.err.println("  Warning: metrics not found in this file: " + missing);
                }
            } else {
                exportCols = headerInfo.columns;
            }

            if (exportCols.isEmpty()) {
                System.err.println("  No metrics to export, skipping.");
                return;
            }

            System.err.printf("  Exporting %d metrics (run=%s)%n", exportCols.size(), resolvedRun);

            final String escapedNode = escapeLabelValue(resolvedNode);
            final String escapedRun = escapeLabelValue(resolvedRun);

            final boolean hasTimeColumn = headerInfo.timeColumnIndex >= 0;
            boolean paddingEmitted = !emitPadding;

            String line;
            int rowIndex = 0;
            while ((line = reader.readLine()) != null) {
                final String[] values = line.split(",", -1);
                if (values.length < 5) {
                    continue;
                }

                final long timestampMs;
                if (hasTimeColumn && headerInfo.timeColumnIndex < values.length) {
                    final long csvTimestampMs = parseTimestamp(values[headerInfo.timeColumnIndex].trim());
                    timestampMs = csvTimestampMs + timeShiftMs;
                } else {
                    timestampMs = (startEpochSeconds + (long) rowIndex * intervalSeconds) * 1000L;
                }

                // On the first row of the first file per node, check if this node starts
                // later than others and emit a padding row at startEpochSeconds to align timelines.
                if (!paddingEmitted) {
                    paddingEmitted = true;
                    final long paddingTs = startEpochSeconds * 1000L;
                    if (timestampMs - paddingTs > 30_000L) {
                        System.err.printf(
                                "  Padding: emitting zero-value row at %d (node starts %ds later)%n",
                                paddingTs, (timestampMs - paddingTs) / 1000);
                        for (final var padEntry : exportCols.entrySet()) {
                            writer.write(escapeMetricName(padEntry.getKey()));
                            writer.write("{node=\"");
                            writer.write(escapedNode);
                            writer.write("\",run=\"");
                            writer.write(escapedRun);
                            writer.write("\"} 0 ");
                            writer.write(Long.toString(paddingTs));
                            writer.write('\n');
                        }
                    }
                }

                for (final var entry : exportCols.entrySet()) {
                    final String metricName = entry.getKey();
                    final int colIndex = entry.getValue();
                    if (colIndex >= values.length) {
                        continue;
                    }

                    final String raw = values[colIndex].trim();
                    final String value = normalizeValue(raw);
                    if (value == null) {
                        continue;
                    }

                    writer.write(escapeMetricName(metricName));
                    writer.write("{node=\"");
                    writer.write(escapedNode);
                    writer.write("\",run=\"");
                    writer.write(escapedRun);
                    writer.write("\"} ");
                    writer.write(value);
                    writer.write(' ');
                    writer.write(Long.toString(timestampMs));
                    writer.write('\n');
                }
                rowIndex++;
            }

            System.err.printf("  Wrote %d data rows%n", rowIndex);
        }
    }

    // --- CSV Parsing ---

    private record HeaderInfo(Map<String, Integer> columns, int timeColumnIndex) {}

    private HeaderInfo findHeaders(@NonNull final BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            // The header row has many columns with underscores (metric names like rounds_per_sec).
            // The category row before it has fewer underscores (simple words like "platform", "app").
            final String[] parts = line.split(",", -1);
            if (parts.length > 20) {
                final long underscoreCount = java.util.Arrays.stream(parts)
                        .filter(p -> !p.isBlank() && p.contains("_"))
                        .count();
                if (underscoreCount > 200) {
                    final Map<String, Integer> columns = new LinkedHashMap<>();
                    int timeIdx = -1;
                    for (int i = 0; i < parts.length; i++) {
                        final String name = parts[i].trim();
                        if (!name.isEmpty()) {
                            columns.put(name, i);
                        }
                        if ("time".equalsIgnoreCase(name)) {
                            timeIdx = i;
                        }
                    }
                    return new HeaderInfo(columns, timeIdx);
                }
            }
        }

        throw new IOException("Could not find metric name header row in CSV");
    }

    // --- Value normalization ---

    private static String normalizeValue(@NonNull final String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return "1";
        }
        if ("false".equalsIgnoreCase(raw)) {
            return "0";
        }
        try {
            Double.parseDouble(raw);
            return raw;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // --- Name escaping ---

    private static String escapeMetricName(@NonNull final String name) {
        final StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == ':') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        // Prometheus metric names must not start with a digit
        if (!sb.isEmpty() && Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static String escapeLabelValue(@NonNull final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // --- Earliest-timestamp detection (for padding restarted nodes) ---

    private long findEarliestTimestamp(@NonNull final List<Path> csvFiles) {
        long earliest = Long.MAX_VALUE;
        for (final Path csvFile : csvFiles) {
            try {
                final long ts = peekFirstTimestamp(csvFile);
                if (ts > 0 && ts < earliest) {
                    earliest = ts;
                }
            } catch (final IOException e) {
                System.err.println("  Warning: could not peek timestamp from " + csvFile + ": " + e.getMessage());
            }
        }
        return earliest;
    }

    /**
     * Reads just enough of a CSV to extract the first data row's timestamp.
     */
    private long peekFirstTimestamp(@NonNull final Path csvFile) throws IOException {
        try (final BufferedReader reader =
                new BufferedReader(new FileReader(csvFile.toFile(), StandardCharsets.UTF_8))) {
            final HeaderInfo headerInfo = findHeaders(reader);
            if (headerInfo.timeColumnIndex < 0) {
                return 0;
            }
            // Read the first data row
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] values = line.split(",", -1);
                if (values.length > headerInfo.timeColumnIndex) {
                    final long ts = parseTimestamp(values[headerInfo.timeColumnIndex].trim());
                    if (ts > 0) {
                        return ts;
                    }
                }
            }
        }
        return 0;
    }

    // --- Timestamp handling ---

    private static final DateTimeFormatter CSV_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private long resolveStartEpoch() {
        if (startTime != null && !startTime.isBlank()) {
            try {
                return Long.parseLong(startTime);
            } catch (final NumberFormatException e) {
                return Instant.parse(startTime).getEpochSecond();
            }
        }
        return Instant.now().getEpochSecond();
    }

    private static long parseTimestamp(@NonNull final String timeStr) {
        try {
            return LocalDateTime.from(CSV_TIME_FORMAT.parse(timeStr)).toEpochSecond(ZoneOffset.UTC) * 1000L;
        } catch (final Exception e) {
            try {
                return Instant.parse(timeStr).toEpochMilli();
            } catch (final Exception e2) {
                return 0;
            }
        }
    }

    // --- Label resolution ---

    private static int extractNodeNumber(@NonNull final String fileName) {
        final Matcher m = CSV_NODE_PATTERN.matcher(fileName);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String resolveNodeId(@NonNull final Path csvFile) {
        if (nodeId != null) {
            return nodeId;
        }
        return String.valueOf(extractNodeNumber(csvFile.getFileName().toString()));
    }

    private String resolveRunName(@NonNull final Path csvFile) {
        if (runName != null) {
            return runName;
        }
        Path parent = csvFile.toAbsolutePath().getParent();
        if (parent != null && parent.getFileName().toString().matches("node\\d+")) {
            parent = parent.getParent();
        }
        return parent != null ? parent.getFileName().toString() : "unknown";
    }

    // --- Filter ---

    private Set<String> buildFilterSet() throws IOException {
        if (metricsFilter != null && !metricsFilter.isBlank()) {
            return new HashSet<>(List.of(metricsFilter.split(",")));
        }
        if (metricsFile != null) {
            final Set<String> set = new HashSet<>();
            for (final String line : Files.readAllLines(metricsFile)) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    set.add(trimmed);
                }
            }
            return set;
        }
        return null;
    }
}
