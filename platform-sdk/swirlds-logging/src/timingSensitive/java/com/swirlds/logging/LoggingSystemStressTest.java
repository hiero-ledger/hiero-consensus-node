// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging;

import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.EXPECTED_STATEMENTS;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.countLinesInStatements;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.getLines;
import static com.swirlds.logging.test.fixtures.util.LoggingTestUtils.linesToStatements;

import com.swirlds.base.test.fixtures.concurrent.TestExecutor;
import com.swirlds.base.test.fixtures.concurrent.WithTestExecutor;
import com.swirlds.base.test.fixtures.io.SystemErrProvider;
import com.swirlds.base.test.fixtures.io.WithSystemError;
import com.swirlds.base.test.fixtures.io.WithSystemOut;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import com.swirlds.logging.api.internal.LoggingSystem;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import com.swirlds.logging.test.fixtures.WithLoggingMirror;
import com.swirlds.logging.test.fixtures.internal.LoggingMirrorImpl;
import com.swirlds.logging.test.fixtures.util.LoggingTestUtils;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WithTestExecutor
@WithLoggingMirror
@WithSystemError
@WithSystemOut
public class LoggingSystemStressTest {

    private static final int TOTAL_RUNNABLE = 100;
    private static final String LOG_FILE = "log-files/logging.log";

    @Inject
    private LoggingMirror loggingMirror;

    @Inject
    private SystemErrProvider errorProvider;

    @Test
    void testMultipleLoggersInParallel(final TestExecutor testExecutor) {
        // given
        final List<Runnable> runnables = IntStream.range(0, TOTAL_RUNNABLE)
                .mapToObj(i -> Loggers.getLogger("logger-" + i))
                .map(l -> (Runnable) () -> LoggingTestUtils.loggExtensively(l))
                .collect(Collectors.toList());

        // when
        testExecutor.executeAndWait(runnables);

        // then
        Assertions.assertEquals(EXPECTED_STATEMENTS * TOTAL_RUNNABLE, loggingMirror.getEventCount());
        IntStream.range(0, TOTAL_RUNNABLE)
                .forEach(i -> Assertions.assertEquals(
                        EXPECTED_STATEMENTS,
                        loggingMirror.getEvents().stream()
                                .filter(e -> Objects.equals(e.loggerName(), "logger-" + i))
                                .count()));
    }

    @Test
    void testOneLoggerInParallel(TestExecutor testExecutor) {
        // given
        final Logger logger = Loggers.getLogger("logger");

        // when
        doLog(testExecutor, logger, TOTAL_RUNNABLE);

        // then
        Assertions.assertEquals(EXPECTED_STATEMENTS * TOTAL_RUNNABLE, loggingMirror.getEventCount());
    }

    @Test
    void testFileLoggingFileMultipleEventsInParallel(final TestExecutor testExecutor) throws IOException {

        // given
        final String logFile = LoggingTestUtils.prepareLoggingFile(LOG_FILE);
        final Configuration configuration = LoggingTestUtils.prepareConfiguration(logFile, "file");
        final LoggingSystem loggingSystem = new LoggingSystem(configuration);
        final LoggingMirrorImpl mirror = new LoggingMirrorImpl();
        loggingSystem.installHandlers();
        loggingSystem.addHandler(mirror);
        // A random log name, so it's easier to combine lines after
        final String loggerName = UUID.randomUUID().toString();
        final Logger logger = loggingSystem.getLogger(loggerName);

        // when
        doLog(testExecutor, logger, 10);
        loggingSystem.stopAndFinalize();

        try {
            final List<String> statementsInMirror = LoggingTestUtils.mirrorToStatements(mirror);
            final List<String> logLines = getLines(logFile);
            final List<String> statementsInFile = linesToStatements(logLines);

            // then
            Assertions.assertEquals(EXPECTED_STATEMENTS * 10, statementsInFile.size());
            final int expectedLineCountInFile = countLinesInStatements(statementsInMirror);
            Assertions.assertEquals(expectedLineCountInFile, (long) logLines.size());
            org.assertj.core.api.Assertions.assertThat(statementsInMirror).isSubsetOf(statementsInFile);

        } finally {
            Files.deleteIfExists(Path.of(logFile));
        }
    }

    private static void doLog(final TestExecutor testExecutor, final Logger logger, final int totalRunnable) {
        testExecutor.executeAndWait(IntStream.range(0, totalRunnable)
                .mapToObj(l -> (Runnable) () -> LoggingTestUtils.loggExtensively(logger))
                .collect(Collectors.toList()));
    }
}
