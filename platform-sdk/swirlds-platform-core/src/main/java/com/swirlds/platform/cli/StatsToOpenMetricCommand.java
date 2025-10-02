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
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import picocli.CommandLine;

@CommandLine.Command(
        name = "stats-to-open-metrics",
        mixinStandardHelpOptions = true,
        description = "Converts list of stat CSV files to OpenMetric format")
@SubcommandOf(PlatformCli.class)
public class StatsToOpenMetricCommand extends AbstractCommand {

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

        this.outputFile = new BufferedWriter(new FileWriter(this.output.toFile()));
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
        this.outputFile.close();

        return 0;
    }

    private void openFile(final Path csvFile) throws IOException {
        this.lineReader = new BufferedReader(new FileReader(csvFile.toFile()));
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

                outputFile.write(
                        (firstHeaders[i].replace(":", "_") + "_" + secondHeaders[i].replace(":", "_")).replace('.', '_')
                                + "{node=\"" + sampleName + "\"} " + values[i] + " "
                                + epochSeconds + "\n");
            }
        }
    }

    private String replaceValue(final String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "1";
        } else if ("false".equalsIgnoreCase(value)) {
            return "0";
        }
        return value;
    }

    private void readRealHeaders() throws IOException {
        firstHeaders = currentLine.split(",");
        secondHeaders = readLine().split(",");
        timeIndex = List.of(secondHeaders).indexOf("time");
    }

    private void skipUselessHeaders() throws IOException {
        while (true) {
            readLine();
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
