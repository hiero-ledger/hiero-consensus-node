// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.support.validators.ExpectedLogProblems.LogProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * Tests for {@link ExpectedLogProblems}.
 */
class ExpectedLogProblemsTest {
    private static final String OPENED_AT = "2026-11-20 16:31:20.000";
    private static final String INSIDE = "2026-11-20 16:31:26.989";
    private static final String BEFORE = "2026-11-20 16:31:19.999";
    private static final String AFTER = "2026-11-20 16:32:00.001";
    private static final String CLOSED_AT = "2026-11-20 16:32:00.000";
    private static final String CONN = "conn=" + "ab".repeat(32);
    private static final LogProblem NOT_FOUND =
            LogProblem.of("ClprSubmitBundleHandler", "step10 DATA connector NOT_FOUND", CONN);

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("A line matching an open window's problem on its network is expected")
    void matchingLineIsExpected() {
        final var network = openWindow(NOT_FOUND);

        assertTrue(ExpectedLogProblems.isExpected(notFoundLine(INSIDE, network, "WARN", CONN)));
        assertTrue(ExpectedLogProblems.isExpected(notFoundLine(OPENED_AT, network, "ERROR", CONN)));
        assertTrue(ExpectedLogProblems.isExpected(notFoundLine(CLOSED_AT, network, "WARN", CONN)));
    }

    @Test
    @DisplayName("The same line on another network is not expected")
    void otherNetworkIsNotExpected() {
        openWindow(NOT_FOUND);

        assertFalse(ExpectedLogProblems.isExpected(notFoundLine(INSIDE, "OTHER_NETWORK", "WARN", CONN)));
    }

    @Test
    @DisplayName("A line without a network tag is not expected")
    void untaggedLineIsNotExpected() {
        openWindow(NOT_FOUND);

        assertFalse(ExpectedLogProblems.isExpected(
                INSIDE + " WARN  108  ClprSubmitBundleHandler - step10 DATA connector NOT_FOUND " + CONN));
    }

    @Test
    @DisplayName("A line logged before the window opened or after it closed is not expected")
    void lineOutsideWindowIsNotExpected() {
        final var network = openWindow(NOT_FOUND);

        assertFalse(ExpectedLogProblems.isExpected(notFoundLine(BEFORE, network, "WARN", CONN)));
        assertFalse(ExpectedLogProblems.isExpected(notFoundLine(AFTER, network, "WARN", CONN)));
    }

    @Test
    @DisplayName("A line from another logger or missing a fragment is not expected")
    void otherLoggerOrMissingFragmentIsNotExpected() {
        final var network = openWindow(NOT_FOUND);

        assertFalse(ExpectedLogProblems.isExpected(INSIDE + " <" + network
                + "-n0> WARN  108  ClprCompleteChannelHandler - step10 DATA connector NOT_FOUND " + CONN));
        assertFalse(ExpectedLogProblems.isExpected(notFoundLine(INSIDE, network, "WARN", "conn=" + "cd".repeat(32))));
    }

    @Test
    @DisplayName("INFO lines never match a problem")
    void infoLineIsNotAProblem() {
        final var network = openWindow(NOT_FOUND);

        assertFalse(ExpectedLogProblems.isExpected(notFoundLine(INSIDE, network, "INFO", CONN)));
    }

    @Test
    @DisplayName("The log validator ignores expected lines but still reports unexpected ones")
    void validatorSkipsOnlyExpectedLines() throws IOException {
        final var network = openWindow(NOT_FOUND);

        final var expectedOnly = logWith(notFoundLine(INSIDE, network, "WARN", CONN));
        assertDoesNotThrow(() -> new HgcaaLogValidator(expectedOnly, Map.of()).validate());

        final var withUnexpected = logWith(
                notFoundLine(INSIDE, network, "WARN", CONN),
                notFoundLine(INSIDE, network, "WARN", "conn=" + "cd".repeat(32)));
        assertThrows(AssertionFailedError.class, () -> new HgcaaLogValidator(withUnexpected, Map.of()).validate());
    }

    /**
     * Opens a window from {@link #OPENED_AT} to {@link #CLOSED_AT} on a network unique to the calling test, since
     * windows are shared by every test in the JVM.
     */
    private static String openWindow(final LogProblem... problems) {
        final var network = "TEST_" + UUID.randomUUID().toString().replace("-", "");
        final var window =
                ExpectedLogProblems.open(network, List.of(problems), ExpectedLogProblems.timestampOf(OPENED_AT));
        window.closeAt(ExpectedLogProblems.timestampOf(CLOSED_AT));
        return network;
    }

    private static String notFoundLine(
            final String timestamp, final String network, final String level, final String conn) {
        return timestamp + " <" + network + "-n0> " + String.format("%-5s", level)
                + " 108  ClprSubmitBundleHandler - [ClprSubmitBundle] step10 DATA connector NOT_FOUND " + conn
                + " receivedMsgId=1";
    }

    private String logWith(final String... lines) throws IOException {
        final var log = tempDir.resolve("hgcaa.log");
        Files.write(log, List.of(lines));
        return log.toString();
    }
}
