// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;

/**
 * Validates {@code swirlds.log} for unexpected WARN/ERROR entries, similar to how
 * {@link HgcaaLogValidator} validates {@code hgcaa.log}. Uses a two-layer allowlist:
 * marker-based filtering (for known benign marker categories) and text-based pattern
 * matching (for specific expected messages).
 */
public class SwirldsLogValidator {
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    /**
     * Regex to extract the marker field from a swirlds.log line. The format is:
     * {@code timestamp <nodeId> seqNum level marker <<thread>> class: message}
     * e.g. {@code 2026-04-14 18:01:33.982 <n0> 177      WARN  SOCKET_EXCEPTIONS <<...>> NetworkUtils: ...}
     */
    private static final Pattern MARKER_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\S+)");

    @NonNull
    private final String logFileLocation;

    public SwirldsLogValidator(@NonNull final String logFileLocation) {
        this.logFileLocation = requireNonNull(logFileLocation);
    }

    public void validate() throws IOException {
        final List<String> problemLines = new ArrayList<>();
        final var problemTracker = new ProblemTracker();
        try (final var stream = Files.lines(Paths.get(logFileLocation))) {
            stream.filter(problemTracker::isProblem)
                    .map(problemTracker::indented)
                    .forEach(problemLines::add);
        }
        if (!problemLines.isEmpty()) {
            Assertions.fail("Found " + problemTracker.numProblems + " problems in swirlds.log '" + logFileLocation
                    + "':\n" + String.join("\n", problemLines));
        }
    }

    private static class ProblemTracker {
        private static final int LINES_AFTER_PROBLEM_TO_REPORT = 10;
        private static final String PROBLEM_DELIMITER = "\n========================================\n";

        /** Markers whose WARN/ERROR lines are always benign in a test context. */
        private static final Set<String> IGNORABLE_MARKERS = Set.of(
                "TESTING_EXCEPTIONS",
                "SOCKET_EXCEPTIONS",
                "TCP_CONNECT_EXCEPTIONS",
                "TESTING_EXCEPTIONS_ACCEPTABLE_RECONNECT");

        /** Text-based patterns: each inner list is a set of strings that must ALL appear for the line to be ignored. */
        private static final List<List<String>> PROBLEM_PATTERNS_TO_IGNORE = List.of(
                List.of("PcesFileTracker", "No preconsensus event files"),
                List.of("PcesFileTracker", "insufficient data to guarantee"),
                List.of("OSHealthChecker"),
                List.of("DefaultSignedStateSentinel", "Old signed state detected"));

        private int numProblems = 0;
        private int linesSinceInitialProblem = -1;
        private int linesToReportAfterInitialProblem = -1;

        boolean isProblem(@NonNull final String line) {
            if (linesSinceInitialProblem >= 0) {
                linesSinceInitialProblem++;
                if (linesSinceInitialProblem > linesToReportAfterInitialProblem) {
                    linesSinceInitialProblem = -1;
                    linesToReportAfterInitialProblem = -1;
                    return false;
                } else {
                    return true;
                }
            } else if (isInitialProblem(line)) {
                if (hasIgnorableMarker(line)) {
                    return false;
                }
                for (final var patterns : PROBLEM_PATTERNS_TO_IGNORE) {
                    if (patterns.stream().allMatch(line::contains)) {
                        return false;
                    }
                }
                numProblems++;
                linesSinceInitialProblem = 0;
                linesToReportAfterInitialProblem = LINES_AFTER_PROBLEM_TO_REPORT;
                return true;
            } else {
                return false;
            }
        }

        String indented(@NonNull final String line) {
            return linesSinceInitialProblem == 0 ? (PROBLEM_DELIMITER + line) : "  " + line;
        }

        private boolean isInitialProblem(@NonNull final String line) {
            return line.contains(WARN) || line.contains(ERROR);
        }

        private boolean hasIgnorableMarker(@NonNull final String line) {
            final var matcher = MARKER_PATTERN.matcher(line);
            if (matcher.find()) {
                return IGNORABLE_MARKERS.contains(matcher.group(1));
            }
            return false;
        }
    }
}
