// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.util;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.api.internal.configuration.ConfigLevelConverter;
import com.swirlds.logging.api.internal.configuration.MarkerStateConverter;
import com.swirlds.logging.api.internal.emergency.EmergencyLoggerImpl;
import com.swirlds.logging.api.internal.format.FormattedLinePrinter;
import com.swirlds.logging.api.internal.level.ConfigLevel;
import com.swirlds.logging.api.internal.level.MarkerState;
import com.swirlds.logging.test.fixtures.internal.LoggingMirrorImpl;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Utility class for logging related operations.
 */
public final class LoggingTestUtils {
    public static final int EXPECTED_STATEMENTS = 14 * 100;

    private static boolean checkIsLogLine(String inputString) {

        for (ConfigLevel logLevel : ConfigLevel.values()) {
            if (inputString.contains(logLevel.name())) {
                return true;
            }
        }
        return false;
    }

    public static List<String> getLines(String path) throws IOException {
        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Converts a list of log lines into a list of statements. Stacktrace and multiple-line logs are grouped together to
     * form statements based on the presence of log levels determined by {@link ConfigLevel}.
     *
     * @param logLines a list of log lines to be converted into statements.
     * @return a list of statements derived from the provided log lines.
     */
    public static List<String> linesToStatements(List<String> logLines) {
        List<String> result = new ArrayList<>();
        StringBuilder previousLine = new StringBuilder();

        for (String line : logLines) {
            if (checkIsLogLine(line)) {
                if (!previousLine.isEmpty()) {
                    result.add(previousLine.toString());
                    previousLine.setLength(0);
                }
                previousLine.append(line);
            } else if (!line.isEmpty()) {
                previousLine.append("\n").append(line);
            }
        }
        if (!previousLine.isEmpty()) {
            result.add(previousLine.toString());
        }

        return result;
    }

    /**
     * Counts the total new line chars in each element of the list and returns number of new line chars in each line
     * plus 1 for each element on the collection
     */
    public static int countLinesInStatements(List<String> strings) {
        int count = strings.size();
        for (String str : strings) {
            for (int i = 0; i < str.length(); i++) {
                if (str.charAt(i) == '\n') {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * extensively log messages into {@code logger} for testing and debugging purposes.
     *
     * @param logger the logger instance to use logging messages
     */
    public static void loggExtensively(Logger logger) {
        IntStream.range(0, 100).forEach(i -> {
            logger.info("L0, Hello world!");
            logger.info("L1, A quick brown fox jumps over the lazy dog.");
            logger.info("L2, Hello world!", new RuntimeException("test"));
            logger.info("L3, Hello {}!", "placeholder");
            logger.info("L4, Hello {}!", new RuntimeException("test"), "placeholder");
            logger.withContext("key", "value").info("L5, Hello world!");
            logger.withMarker("marker").info("L6, Hello world!");
            logger.withContext("user-id", UUID.randomUUID().toString()).info("L7, Hello world!");
            logger.withContext("user-id", UUID.randomUUID().toString())
                    .info("L8, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!", 1, 2, 3, 4, 5, 6, 7, 8, 9);
            logger.withContext("user-id", UUID.randomUUID().toString())
                    .info(
                            "L9, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!",
                            new RuntimeException("test"),
                            1,
                            2,
                            3,
                            4,
                            5,
                            6,
                            7,
                            8,
                            9);
            logger.withContext("user-id", UUID.randomUUID().toString())
                    .withContext("key", "value")
                    .info("L10, Hello world!");
            logger.withMarker("marker").info("L11, Hello world!");
            logger.withMarker("marker1").withMarker("marker2").info("L12, Hello world!");
            logger.withContext("key", "value")
                    .withMarker("marker1")
                    .withMarker("marker2")
                    .info("L13, Hello {}, {}, {}, {}, {}, {}, {}, {}, {}!", 1, 2, 3, 4, 5, 6, 7, 8, 9);
        });
    }

    public static List<String> mirrorToStatements(final LoggingMirrorImpl mirror) {
        final FormattedLinePrinter formattedEvents = new FormattedLinePrinter(false);
        return mirror.getEvents().stream()
                .map(e -> {
                    final StringBuilder stringBuilder = new StringBuilder();
                    formattedEvents.print(stringBuilder, e);
                    stringBuilder.setLength(stringBuilder.length() - 1);
                    return stringBuilder.toString();
                })
                .collect(Collectors.toList());
    }

    public static Configuration prepareConfiguration(final String logFile, final String fileHandlerName) {
        return getConfigBuilder()
                .withValue("logging.level", "trace")
                .withValue("logging.handler.%s.type".formatted(fileHandlerName), "file")
                .withValue("logging.handler.%s.enabled".formatted(fileHandlerName), "true")
                .withValue("logging.handler.%s.formatTimestamp".formatted(fileHandlerName), "false")
                .withValue("logging.handler.%s.level".formatted(fileHandlerName), "trace")
                .withValue("logging.handler.%s.file".formatted(fileHandlerName), logFile)
                .getOrCreateConfig();
    }

    public static String prepareLoggingFile(final String logFile) throws IOException {
        final File testMultipleLoggersInParallel = new File(logFile);
        Files.deleteIfExists(testMultipleLoggersInParallel.toPath());
        return testMultipleLoggersInParallel.getAbsolutePath();
    }

    public static TestConfigBuilder getConfigBuilder() {
        return new TestConfigBuilder()
                .withConverter(MarkerState.class, new MarkerStateConverter())
                .withConverter(ConfigLevel.class, new ConfigLevelConverter());
    }

    public static List<LogEvent> getEmergencyLoggerEvents(final Level level) {
        return EmergencyLoggerImpl.getInstance().publishLoggedEvents().stream()
                .filter(event -> event.level() == level)
                .collect(Collectors.toList());
    }

    public static LoggingSystem loggingSystemWithHandlers(final Configuration configuration, LogHandler... handlers) {
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        loggingSystem.installHandlers();
        for (LogHandler handler : handlers) {
            loggingSystem.addHandler(handler);
        }
        return loggingSystem;
    }
}
