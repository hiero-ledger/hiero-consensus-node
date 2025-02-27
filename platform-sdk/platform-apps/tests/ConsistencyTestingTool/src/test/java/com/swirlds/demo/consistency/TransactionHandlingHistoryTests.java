// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionHandlingHistoryTests {
    @TempDir
    private static Path tempDir;

    private Path logFilePath;

    @BeforeEach
    void setUp() {
        logFilePath = tempDir.resolve("logFile");

        try {
            // delete the log file if it exists
            Files.deleteIfExists(logFilePath);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the contents of the log file as a string
     *
     * @return the contents of the log file as a string
     */
    private String getLogFileContents() {
        try {
            return Files.readString(logFilePath);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("New rounds are added to history and logged to file")
    void newRoundsHandled() {
        assertFalse(Files.exists(logFilePath), "Log file shouldn't exist yet");
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(2, 22, List.of(6L, 5L, 4L));

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertTrue(Files.exists(logFilePath), "Log file should exist");
            assertEquals(1, getLogFileContents().lines().count(), "Log file should have 1 round recorded");

            assertTrue(history.processRound(round2).isEmpty(), "No errors should have occurred");
            assertEquals(2, getLogFileContents().lines().count(), "Log file should have 2 rounds recorded");
        }
    }

    @Test
    @DisplayName("Duplicate transactions are detected")
    void duplicateTransaction() {
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(2, 22, List.of(3L, 5L, 4L));

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertEquals(1, history.processRound(round2).size(), "An error should have occurred");

            assertEquals(2, getLogFileContents().lines().count(), "Log file should have 2 rounds recorded");
        }
    }

    @Test
    @DisplayName("A new round that matches a historical round is handled correctly")
    void historicalRound() {
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));

            // exactly the same as the first round
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertTrue(history.processRound(round2).isEmpty(), "No errors should have occurred");

            assertEquals(1, getLogFileContents().lines().count(), "Log file should have only have 1 round recorded");
        }
    }

    @Test
    @DisplayName("A new round that matches a historical round but with incorrect state is detected")
    void historicalRoundWithIncorrectState() {
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));

            // exactly the same as the first round, but with incorrect state
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(1, 66, List.of(1L, 2L, 3L));

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertEquals(1, history.processRound(round2).size(), "An error should have occurred");

            assertEquals(1, getLogFileContents().lines().count(), "Log file should have only have 1 round recorded");
        }
    }

    @Test
    @DisplayName("A new round that matches a historical round but with incorrect transactions is detected")
    void historicalRoundWithIncorrectTransactions() {
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));

            // exactly the same as the first round, but with an extra transaction
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L, 4L));

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertEquals(1, history.processRound(round2).size(), "An error should have occurred");

            assertEquals(1, getLogFileContents().lines().count(), "Log file should have only have 1 round recorded");
        }
    }

    @Test
    @DisplayName("A new round with non-increasing round number is detected")
    void nonIncreasingRoundNumber() {
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(3, 1, List.of(1L, 2L, 3L));
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(2, 22, List.of(6L, 5L, 4L));

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertEquals(1, history.processRound(round2).size(), "An error should have occurred");

            assertEquals(2, getLogFileContents().lines().count(), "Log file should have 2 rounds recorded");
        }
    }

    @Test
    @DisplayName("History that has been parsed from file is equivalent to the normal history")
    void parsedHistory() {
        try (final TransactionHandlingHistory history = new TransactionHandlingHistory();
                final TransactionHandlingHistory parsedHistory = new TransactionHandlingHistory()) {
            history.init(logFilePath);

            final ConsistencyTestingToolRound round1 = new ConsistencyTestingToolRound(1, 1, List.of(1L, 2L, 3L));
            final ConsistencyTestingToolRound round2 = new ConsistencyTestingToolRound(2, 22, List.of(6L, 5L, 4L));
            final ConsistencyTestingToolRound round3 = new ConsistencyTestingToolRound(3, 33, List.of());

            assertTrue(history.processRound(round1).isEmpty(), "No errors should have occurred");
            assertTrue(history.processRound(round2).isEmpty(), "No errors should have occurred");
            assertTrue(history.processRound(round3).isEmpty(), "No errors should have occurred");

            assertEquals(3, getLogFileContents().lines().count(), "Log file should have 3 rounds recorded");

            parsedHistory.init(logFilePath);

            // rounds matching historical rounds are handled correctly
            final ConsistencyTestingToolRound round2Duplicate =
                    new ConsistencyTestingToolRound(2, 22, List.of(6L, 5L, 4L));
            assertTrue(parsedHistory.processRound(round2Duplicate).isEmpty(), "No errors should have occurred");
            assertEquals(3, getLogFileContents().lines().count(), "Log file should have 4 rounds recorded");

            // rounds that don't match historical rounds are handled correctly
            final ConsistencyTestingToolRound incorrectRound1Duplicate =
                    new ConsistencyTestingToolRound(1, 65, List.of(1L, 2L, 3L));
            assertEquals(
                    1, parsedHistory.processRound(incorrectRound1Duplicate).size(), "An error should have occurred");
            assertEquals(3, getLogFileContents().lines().count(), "Log file should have 4 rounds recorded");

            final ConsistencyTestingToolRound incorrectRound3Duplicate =
                    new ConsistencyTestingToolRound(3, 33, List.of(1L, 2L, 3L));
            assertEquals(
                    1, parsedHistory.processRound(incorrectRound3Duplicate).size(), "An error should have occurred");
            assertEquals(3, getLogFileContents().lines().count(), "Log file should have 4 rounds recorded");

            // new rounds can be recorded
            final ConsistencyTestingToolRound round4 = new ConsistencyTestingToolRound(4, 44, List.of(7L, 8L, 9L));

            assertTrue(parsedHistory.processRound(round4).isEmpty(), "No errors should have occurred");
            assertEquals(4, getLogFileContents().lines().count(), "Log file should have 4 rounds recorded");
        }
    }
}
