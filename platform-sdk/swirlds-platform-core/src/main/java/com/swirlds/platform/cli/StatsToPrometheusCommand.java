// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.IntStream;
import picocli.CommandLine;

@CommandLine.Command(
        name = "stats-to-prometheus",
        mixinStandardHelpOptions = true,
        description = "Converts list of stat CSV files to prometheus format")
@SubcommandOf(PlatformCli.class)
public class StatsToPrometheusCommand extends AbstractCommand {

    private List<Path> csvFiles;
    private BufferedReader lineReader;
    private String currentLine;
    private String[] firstHeaders;
    private String[] secondHeaders;
    private int timeIndex;
    private String sampleName;
    private Path output;
    private BufferedWriter outputFile;

    @CommandLine.Parameters(description = "The csv stat files to read")
    private void setTestData(@NonNull final List<Path> csvFiles) {
        this.csvFiles = csvFiles;
    }

    @CommandLine.Option(
            names = {"-o", "--output-file"},
            description = "Path to output file for resulting metrics")
    private void setFile(@NonNull final Path file) {
        this.output = file;
    }

    @Override
    public Integer call() throws Exception {

        this.outputFile = new BufferedWriter(new FileWriter(this.output.toFile(), StandardCharsets.UTF_8));
        try {
            for (final Path csvFile : this.csvFiles) {
                System.out.println("Processing stat file: " + csvFile);
                openFile(csvFile);
                try {
                    skipUselessHeaders();
                    readRealHeaders();
                    processDataLines();
                } finally {
                    this.lineReader.close();
                }
            }

            this.outputFile.write("# EOF\n");
        } finally {
            this.outputFile.close();
        }

        return 0;
    }

    private void openFile(final Path csvFile) throws IOException {
        this.lineReader = new BufferedReader(new FileReader(csvFile.toFile(), StandardCharsets.UTF_8));
        this.sampleName = csvFile.getFileName().toString();
        this.sampleName = this.sampleName.substring(0, this.sampleName.lastIndexOf("."));
    }

    private void processDataLines() throws IOException {
        final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");
        while (readLine() != null) {
            final String[] values = currentLine.split(",");
            final long epochSeconds =
                    LocalDateTime.from(dateFormat.parse(values[timeIndex])).toEpochSecond(ZoneOffset.UTC);

            for (int i = 0; i < firstHeaders.length; i++) {
                if (firstHeaders[i].isBlank()) {
                    continue;
                }
                if (i == timeIndex) {
                    continue;
                }
                values[i] = replaceValue(values[i]);

                outputFile.write(escapeMetricName(firstHeaders[i] + "_" + secondHeaders[i])
                        + "{node=\"" + escapeMetricName(sampleName) + "\"} " + values[i] + " "
                        + epochSeconds + "\n");
            }
        }
    }

    private String escapeMetricName(final String metricName) {
        final StringBuilder escapedMetricName = new StringBuilder(metricName.length());
        for (int i = 0; i < metricName.length(); i++) {
            final char c = metricName.charAt(i);
            // for simplicity, allow only a-zA-Z0-9
            if (c > 127 || !(Character.isAlphabetic(c) || Character.isDigit(c))) {
                escapedMetricName.append("_");
            } else {
                escapedMetricName.append(c);
            }
        }
        return escapedMetricName.toString();
    }

    private String replaceValue(final String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "1";
        } else if ("false".equalsIgnoreCase(value)) {
            return "0";
        }
        if (value == null) {
            return "0";
        }
        return value.trim();
    }

    private void readRealHeaders() throws IOException {
        firstHeaders = currentLine.split(",");
        secondHeaders = readLine().split(",");
        timeIndex = IntStream.range(0, secondHeaders.length)
                .filter(i -> "time".equalsIgnoreCase(secondHeaders[i].trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No 'time' column found in second header line"));
    }

    private void skipUselessHeaders() throws IOException {
        while (true) {
            readLine();
            if (currentLine == null) {
                throw new RuntimeException(
                        "File does not seem to be a stats file, missing a line starting with double comma (,,)");
            }
            if (!currentLine.startsWith(",,")) {
                continue;
            }
            if (currentLine.isBlank()) {
                continue;
            }
            break;
        }
    }

    private String readLine() throws IOException {
        currentLine = lineReader.readLine();
        return currentLine;
    }
}
