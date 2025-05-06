// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Class that processes the swirlds.log files and generates an HTML file with the logs formatted.
 */
public class LogProcessor {
    private static final Logger logger = LogManager.getLogger(LogProcessor.class);

    /**
     * Defensively abort log processing if there are too many lines in all log files combined.
     */
    private static final int MAX_LINES = 500000;

    /**
     * Hidden constructor.
     */
    private LogProcessor() {}

    /**
     * Find all the swirlds.log files in the input directory.
     *
     * @return a map of node id to the path of the swirlds.log file for that node
     * @throws IOException if an IO error occurs
     */
    @NonNull
    private static Map<NodeId, Path> findLogFiles(@NonNull final Path inputDirectory) throws IOException {
        final Map<NodeId, Path> logFilesByNode = new HashMap<>();

        // look for node directories in the inputDirectory. if you find a node directory, look for a swirlds.log file
        // in that directory. if you find one, add it to the map.
        try (final Stream<Path> nodeDirectories = Files.list(inputDirectory).filter(Files::isDirectory)) {
            // look for swirlds.log files in each subdirectory
            nodeDirectories.forEach(subdirectory -> {
                final String subdirectoryName = subdirectory.getFileName().toString();

                final Matcher nodeDirectoryMatcher =
                        Pattern.compile("node0*(\\d+)").matcher(subdirectoryName);

                // ignore subdirectories that don't match the node directory pattern
                if (!nodeDirectoryMatcher.matches()) {
                    return;
                }

                final Path logFile = subdirectory.resolve("swirlds.log");
                if (Files.exists(logFile)) {
                    final NodeId nodeId = NodeId.of(Integer.parseInt(subdirectoryName.substring(4)));
                    logFilesByNode.put(nodeId, logFile);

                    logger.info(LogMarker.CLI.getMarker(), "Found log file: {}", logFile);
                }
            });
        }

        return logFilesByNode;
    }

    /**
     * Process the logs and generate the HTML file.
     *
     * @throws IOException if an IO error occurs
     */
    public static void processLogs(@Nullable final Path inputDirectory, @Nullable final Path outputPath)
            throws IOException {

        final Path actualInputDirectory = Objects.requireNonNullElseGet(inputDirectory, FileUtils::getAbsolutePath);
        final Path actualOutputPath =
                Objects.requireNonNullElseGet(outputPath, () -> actualInputDirectory.resolve("swirlds-logs.html"));

        final Map<NodeId, Path> logFilesByNode = findLogFiles(actualInputDirectory);

        final Map<NodeId, List<String>> logLinesByNode = new HashMap<>();

        long lineCount = 0;
        for (final Map.Entry<NodeId, Path> entry : logFilesByNode.entrySet()) {
            final List<String> logLines;
            try (final BufferedReader reader =
                    new BufferedReader(new FileReader(entry.getValue().toFile()))) {
                logLines = reader.lines().toList();
            }

            lineCount += logLines.size();
            if (lineCount > MAX_LINES) {
                logger.info(LogMarker.CLI.getMarker(), "Aborting log processing because there are too many lines");
                return;
            }

            logLinesByNode.put(entry.getKey(), logLines);
        }

        final String htmlPage = HtmlGenerator.generateHtmlPage(logLinesByNode);

        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(actualOutputPath.toString(), false))) {

            writer.write(htmlPage);
        } catch (final IOException e) {
            logger.info(LogMarker.CLI.getMarker(), "Failed to write formatted log file to {}", outputPath);
        }
    }
}
