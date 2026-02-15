// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses MainNetStats CSV files and extracts a named metric column as a list of doubles.
 */
public final class StatsFileParser {

    private StatsFileParser() {}

    /**
     * Extracts values for the given metric from a CSV file.
     *
     * @param file   path to a MainNetStats CSV file
     * @param index the column to extract
     * @return list of values per time-step ({@code null} entries for missing data)
     * @throws IOException if the file cannot be read
     */
    @NonNull
    public static List<Double> parseColumn(@NonNull final Path file, @NonNull final Integer index) throws IOException {
        final List<Double> values = new ArrayList<>();
        try (final var reader = Files.newBufferedReader(file)) {
            while (!reader.readLine().isBlank()) {
                // ignore
            }
            reader.readLine(); // one for category
            reader.readLine(); // one for name
            String line;
            while ((line = reader.readLine()) != null) {
                // Resets in mainet often produce that the headers are printed again in the file
                // this parsing logic will fail for those, we should add something to detect the case
                // probably if the line is empty it means that next two will be headers
                final String[] cols = line.split(",", -1);
                if (index < cols.length) {
                    final String cell = cols[index].trim();
                    if (cell.isEmpty()) {
                        values.add(null);
                    } else {
                        try {
                            values.add(Double.parseDouble(cell));
                        } catch (final NumberFormatException e) {
                            values.add(null);
                        }
                    }
                } else {
                    values.add(null);
                }
            }
            return values;
        }
    }

    /**
     * Parses the description header lines from a MainNetStats CSV file.
     *
     * <p>Lines before the data header have the format {@code metricName:,description,}.
     *
     * @return list of parsed metrics with name, header index, and description
     */
    @NonNull
    public static List<ParsableMetric> availableMetrics(@NonNull final Map<String, Path> files) {
        final Map<String, String> metricsDescriptionsMap = new HashMap<>();
        final Map<String, Map<String, Integer>> metricsColumnsInFile = new HashMap<>();
        files.forEach((key, value) -> {
            try (final var reader = Files.newBufferedReader(value)) {
                String line = reader.readLine();
                while (!line.isBlank()) {
                    final int colonIdx = line.indexOf(':');
                    if (colonIdx > 0 && colonIdx < line.length() - 1) {
                        final String name = line.substring(0, colonIdx).trim();
                        // After "name:," the description follows, possibly ending with ","
                        String rest = line.substring(colonIdx + 1);
                        if (rest.startsWith(",")) {
                            rest = rest.substring(1);
                        }
                        if (rest.endsWith(",")) {
                            rest = rest.substring(0, rest.length() - 1);
                        }
                        rest = rest.trim();
                        if (!name.isEmpty() && !rest.isEmpty() && !name.equals("filename")) {
                            metricsDescriptionsMap.putIfAbsent(name, rest);
                        }
                    }

                    line = reader.readLine();
                }

                // now the next line we read will be the headers one:
                reader.readLine(); // one for category
                line = reader.readLine(); // one for name
                final String[] headers = line.split(",", -1);

                for (int i = 0; i < headers.length; i++) {
                    if (!headers[i].isEmpty() && !headers[i].equals("filename")) {
                        metricsColumnsInFile
                                .computeIfAbsent(headers[i], s -> new HashMap<>())
                                .put(key, i);
                    }
                }

            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        return metricsColumnsInFile.entrySet().stream()
                .map(e -> new ParsableMetric(
                        e.getKey(),
                        e.getValue(),
                        metricsDescriptionsMap.getOrDefault(e.getKey(), "No description for metric: " + e.getKey())))
                .toList();
    }
}
