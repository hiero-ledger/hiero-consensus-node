// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_LOG;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.support.validators.ExpectedLogProblems;
import com.hedera.services.bdd.junit.support.validators.ExpectedLogProblems.LogProblem;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Declares the WARN and ERROR log lines a test deliberately causes, so log validation does not report them.
 *
 * <p>Put it before the ops that cause the problems: lines are expected from when it runs until {@link #LOGGING_GRACE}
 * after the spec finishes, and only on the spec's target network. It may be nested, e.g. inside a
 * {@code sourcingContextual} that needs values only known at runtime. When the spec finishes, {@link HapiSpec} calls
 * {@link #close}, which fails the spec if an expected problem never appeared, so an expectation cannot outlive the
 * behavior it documents.
 */
public class ExpectLogProblemsOp extends UtilOp {
    /** How long after the spec finishes its expected lines may still be logged, e.g. by a node that is behind. */
    private static final Duration LOGGING_GRACE = Duration.ofSeconds(10);
    /** How long to wait for an expected line to appear once the spec has finished. */
    private static final Duration APPEARANCE_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final List<LogProblem> problems;
    private ExpectedLogProblems.Window window;

    public ExpectLogProblemsOp(@NonNull final List<LogProblem> problems) {
        this.problems = List.copyOf(problems);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) {
        // Log timestamps have millisecond precision, so open at the start of the current millisecond
        window = ExpectedLogProblems.open(
                spec.targetNetworkOrThrow().name(), problems, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        spec.registerExpectedLogProblems(this);
        return false;
    }

    /**
     * Closes the window once the spec has finished. If the spec passed, first waits for every expected problem to
     * appear on some node of its network.
     *
     * @param spec the finished spec
     * @param passed whether the spec passed
     * @throws AssertionError if the spec passed but an expected problem never appeared
     */
    public void close(@NonNull final HapiSpec spec, final boolean passed) {
        requireNonNull(spec);
        if (window == null) {
            return;
        }
        try {
            if (passed) {
                awaitProblems(spec);
            }
        } finally {
            window.closeAt(Instant.now().plus(LOGGING_GRACE));
        }
    }

    private void awaitProblems(@NonNull final HapiSpec spec) {
        final var logs = spec.targetNetworkOrThrow().nodes().stream()
                .map(node -> node.getExternalPath(APPLICATION_LOG))
                .toList();
        final var missing = new ArrayList<>(problems);
        final var deadline = Instant.now().plus(APPEARANCE_TIMEOUT);
        while (true) {
            missing.removeIf(problem -> appeared(problem, logs));
            if (missing.isEmpty()) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Expected log problems never appeared on network '"
                        + spec.targetNetworkOrThrow().name() + "' after " + window.from() + ": " + missing);
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for expected log problems", e);
            }
        }
    }

    private boolean appeared(@NonNull final LogProblem problem, @NonNull final List<Path> logs) {
        for (final var log : logs) {
            if (!Files.exists(log)) {
                continue;
            }
            try (Stream<String> lines = Files.lines(log)) {
                if (lines.anyMatch(line -> problem.matches(line) && isAfterOpening(line))) {
                    return true;
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return false;
    }

    private boolean isAfterOpening(@NonNull final String line) {
        final var at = ExpectedLogProblems.timestampOfLine(line);
        return at != null && !at.isBefore(window.from());
    }

    @Override
    public String toString() {
        return "ExpectLogProblemsOp" + problems;
    }
}
