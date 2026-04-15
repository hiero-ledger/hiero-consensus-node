// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/**
 * Extracts a desired list of metric from a Hedera stats CSV and writes a simple two-column CSV
 * with human-readable timestamps.
 *
 * <p>Example output:
 * <pre>
 * time,rounds_per_sec
 * 2026-03-14 09:15:00,4.76
 * 2026-03-14 09:15:03,4.81
 * </pre>
 */
@CommandLine.Command(
        name = "stats-extract",
        mixinStandardHelpOptions = true,
        description = "Extracts a sublist of metrics from a stats CSV into a simple time,valueA,valueB CSV")
@SubcommandOf(Pcli.class)
public class StatsExtractCommand extends AbstractCommand {

    private static final DateTimeFormatter CSV_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");
    private static final DateTimeFormatter OUTPUT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    @CommandLine.Parameters(index = "0", description = "Input stats CSV file")
    private Path input;

    @CommandLine.Parameters(
            index = "1..*",
            arity = "1..*",
            description = "Metric name(s) to extract (e.g. rounds_per_sec unhealthyDuration)")
    private List<String> metricNames;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Output CSV file (default: <first_metric_name>.csv in current directory)")
    private Path output;

    @Override
    public Integer call() throws Exception {
        if (!input.toFile().isFile()) {
            System.err.println("Input file not found: " + input);
            return 1;
        }

        final Path outputPath = output != null ? output : Path.of(metricNames.getFirst() + ".csv");

        try (final BufferedReader reader = new BufferedReader(new FileReader(input.toFile(), StandardCharsets.UTF_8));
                final BufferedWriter writer =
                        new BufferedWriter(new FileWriter(outputPath.toFile(), StandardCharsets.UTF_8))) {

            final HeaderInfo headerInfo = findHeaders(reader);

            // Resolve column indices for each requested metric
            final List<Integer> metricIndices = new ArrayList<>();
            final List<String> resolvedNames = new ArrayList<>();
            for (final String name : metricNames) {
                final Integer idx = headerInfo.columns.get(name);
                if (idx == null) {
                    System.err.println("Metric '" + name + "' not found in CSV.");
                    System.err.println("Available metrics (first 20):");
                    headerInfo.columns.keySet().stream().limit(20).forEach(k -> System.err.println("  " + k));
                    System.err.println("  ... (" + headerInfo.columns.size() + " total)");
                    return 1;
                }
                metricIndices.add(idx);
                resolvedNames.add(name);
            }

            // Write header
            writer.write("time");
            for (final String name : resolvedNames) {
                writer.write(',');
                writer.write(name);
            }
            writer.newLine();

            int rowIndex = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] values = line.split(",", -1);
                if (values.length < 5) {
                    continue;
                }

                // Resolve timestamp
                final String timeStr;
                if (headerInfo.timeColumnIndex >= 0 && headerInfo.timeColumnIndex < values.length) {
                    final long ms = parseTimestamp(values[headerInfo.timeColumnIndex].trim());
                    timeStr = OUTPUT_TIME_FORMAT.format(Instant.ofEpochMilli(ms));
                } else {
                    timeStr = "row_" + rowIndex;
                }

                // Write time and all metric values
                writer.write(timeStr);
                for (final int idx : metricIndices) {
                    writer.write(',');
                    if (idx < values.length) {
                        writer.write(values[idx].trim());
                    }
                }
                writer.newLine();

                rowIndex++;
            }

            System.err.printf("Extracted %d rows of %d metric(s) to %s%n", rowIndex, resolvedNames.size(), outputPath);
        }

        return 0;
    }

    // --- Header parsing (same logic as StatsToPrometheusCommand) ---

    private record HeaderInfo(Map<String, Integer> columns, int timeColumnIndex) {}

    private HeaderInfo findHeaders(@NonNull final BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
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

    // --- Timestamp parsing ---

    private static long parseTimestamp(@NonNull final String timeStr) {
        try {
            return java.time.LocalDateTime.from(CSV_TIME_FORMAT.parse(timeStr)).toEpochSecond(ZoneOffset.UTC) * 1000L;
        } catch (final Exception e) {
            try {
                return Instant.parse(timeStr).toEpochMilli();
            } catch (final Exception e2) {
                return 0;
            }
        }
    }
}
