// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

import com.hedera.hapi.node.base.Key;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;

public class HgcaaLogValidator {
    private static final String OVERRIDE_PREFIX = "Override admin key for";
    private static final String OVERRIDE_NODE_ADMIN_KEY_PATTERN = OVERRIDE_PREFIX + " node%d is :: %s";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    private static final String POSSIBLY_CATASTROPHIC = "Possibly CATASTROPHIC";
    private final String logFileLocation;
    private final Map<Long, Key> overrideNodeAdminKeys;

    public HgcaaLogValidator(
            @NonNull final String logFileLocation, @NonNull final Map<Long, Key> overrideNodeAdminKeys) {
        this.logFileLocation = requireNonNull(logFileLocation);
        this.overrideNodeAdminKeys = requireNonNull(overrideNodeAdminKeys);
    }

    public void validate() throws IOException {
        final List<String> problemLines = new ArrayList<>();
        final var problemTracker = new ProblemTracker();
        final Set<String> missingNodeAdminKeyOverrides = overrideNodeAdminKeys.entrySet().stream()
                .map(e -> String.format(OVERRIDE_NODE_ADMIN_KEY_PATTERN, e.getKey(), e.getValue()))
                .collect(toCollection(HashSet<String>::new));
        final Consumer<String> adminKeyOverrides = l -> {
            if (l.contains(OVERRIDE_PREFIX)) {
                missingNodeAdminKeyOverrides.removeIf(l::contains);
            }
        };
        try (final var stream = Files.lines(Paths.get(logFileLocation))) {
            stream.peek(adminKeyOverrides)
                    .filter(problemTracker::isProblem)
                    .map(problemTracker::indented)
                    .forEach(problemLines::add);
        }
        missingNodeAdminKeyOverrides.forEach(o -> {
            problemLines.add("MISSING - " + o);
            problemTracker.numProblems++;
        });
        if (!problemLines.isEmpty()) {
            Assertions.fail("Found " + problemTracker.numProblems + " problems in log file '" + logFileLocation + "':\n"
                    + String.join("\n", problemLines));
        }
    }

    private static class ProblemTracker {
        private static final int LINES_AFTER_NON_CATASTROPHIC_PROBLEM_TO_REPORT = 10;
        private static final int LINES_AFTER_CATASTROPHIC_PROBLEM_TO_REPORT = 30;
        private static final String PROBLEM_DELIMITER = "\n========================================\n";

        private static final List<List<String>> PROBLEM_PATTERNS_TO_IGNORE = List.of(
                List.of("NodeMetadataHelper"),
                List.of("not in the address book"),
                List.of("Specified TLS cert", "doesn't exist"),
                // Stopping an embedded node can interrupt signature verification of background traffic
                List.of("Interrupted while waiting for signature verification"),
                List.of("Could not start TLS server, will continue without it"),
                List.of("Properties file", "does not exist and won't be used as configuration source"),
                // Using a 1-minute staking period in CI can lead to periods with no transactions, breaking invariants
                List.of("StakingRewardsHelper", "Pending rewards decreased"),
                // Some PR checks don't stake any HBAR, so after crossing a staking boundary all nodes
                // have zero weight and the RosterStore rejects a zero-weight roster as invalid
                List.of("Candidate roster was rejected"),
                List.of("Throttle multiplier for CryptoTransfer throughput congestion has no throttle buckets"),
                // Although we do want a little more visibility for these messages, they shouldn't fail a CI run
                List.of("Proof future for construction", "must wait until previous finished"),
                List.of("Ignoring forced handoff to incomplete construction"),
                List.of("Completing signing attempt without obtaining a signature"),
                List.of("No pending blocks found"),
                List.of("Forcing handoff to construction", "with different target roster"),
                List.of("HintsSubmissions", "Failed to submit", "(PLATFORM_NOT_ACTIVE)"),
                List.of("Ignoring invalid partial signature"));

        private int numProblems = 0;
        private int linesSinceInitialProblem = -1;
        private int linesToReportAfterInitialProblem = -1;

        boolean isProblem(final String line) {
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
                for (final var patterns : PROBLEM_PATTERNS_TO_IGNORE) {
                    if (patterns.stream().allMatch(line::contains)) {
                        return false;
                    }
                }
                numProblems++;
                linesSinceInitialProblem = 0;
                linesToReportAfterInitialProblem = isPossiblyCatastrophicProblem(line)
                        ? LINES_AFTER_CATASTROPHIC_PROBLEM_TO_REPORT
                        : LINES_AFTER_NON_CATASTROPHIC_PROBLEM_TO_REPORT;
                return true;
            } else {
                return false;
            }
        }

        String indented(final String line) {
            return linesSinceInitialProblem == 0 ? (PROBLEM_DELIMITER + line) : "  " + line;
        }

        private boolean isInitialProblem(final String line) {
            return line.contains(WARN) || line.contains(ERROR);
        }

        private boolean isPossiblyCatastrophicProblem(final String line) {
            return line.contains(POSSIBLY_CATASTROPHIC);
        }
    }
}
