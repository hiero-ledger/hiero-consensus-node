// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * The WARN and ERROR log lines that tests expect, so {@link HgcaaLogValidator} does not report them as problems.
 *
 * <p>Unlike the validator's global ignore list, each expectation is bound to the test that registered it: a line is
 * expected only if it was logged on that test's network, while that test ran, by the given logger, and contains all
 * of the given fragments. The same message from any other test still fails validation.
 */
public final class ExpectedLogProblems {
    /**
     * Matches a log line's timestamp, optional {@code <network-nN>} tag, level and logger, e.g.
     * {@code 2026-11-20 16:31:26.989 <ledgerA-n0> WARN  108  ClprSubmitBundleHandler - ...}.
     */
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} [\\d:.]+) (?:<(?<network>[^>]*?)(?:-n\\d+)?> )?(?<level>WARN|ERROR)\\s+\\d+\\s+"
                    + "(?<logger>\\S+) - ");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final List<Window> WINDOWS = new CopyOnWriteArrayList<>();

    private ExpectedLogProblems() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A WARN or ERROR log line a test expects: logged by {@code logger}, containing every one of {@code fragments}.
     *
     * @param logger the simple name of the logging class
     * @param fragments text the line must contain, e.g. the message and an id unique to the test
     */
    public record LogProblem(
            @NonNull String logger, @NonNull List<String> fragments) {
        public LogProblem {
            requireNonNull(logger);
            fragments = List.copyOf(fragments);
        }

        /**
         * Returns a problem logged by {@code logger} containing every one of {@code fragments}.
         *
         * @param logger the simple name of the logging class
         * @param fragments text the line must contain
         * @return the problem
         */
        public static LogProblem of(@NonNull final String logger, @NonNull final String... fragments) {
            return new LogProblem(logger, List.of(fragments));
        }

        /**
         * Returns whether {@code line} is a WARN or ERROR line logged by this problem's logger with all its fragments.
         *
         * @param line a log line
         * @return whether the line is this problem
         */
        public boolean matches(@NonNull final String line) {
            final var matcher = LINE.matcher(line);
            return matcher.find()
                    && logger.equals(matcher.group("logger"))
                    && fragments.stream().allMatch(line::contains);
        }

        @Override
        public String toString() {
            return logger + " " + fragments;
        }
    }

    /**
     * The problems a test expects on one network, from when it registered them until shortly after it finished.
     */
    public static final class Window {
        private final String networkName;
        private final List<LogProblem> problems;
        private final Instant from;
        private volatile Instant until = Instant.MAX;

        private Window(
                @NonNull final String networkName,
                @NonNull final List<LogProblem> problems,
                @NonNull final Instant from) {
            this.networkName = requireNonNull(networkName);
            this.problems = List.copyOf(problems);
            this.from = requireNonNull(from);
        }

        /**
         * Returns when the window opened.
         *
         * @return the opening time
         */
        public Instant from() {
            return from;
        }

        /**
         * Returns the expected problems.
         *
         * @return the problems
         */
        public List<LogProblem> problems() {
            return problems;
        }

        /**
         * Closes the window at {@code until}; lines logged later are no longer expected.
         *
         * @param until the closing time
         */
        public void closeAt(@NonNull final Instant until) {
            this.until = requireNonNull(until);
        }

        private boolean covers(@NonNull final String network, @NonNull final Instant at) {
            return networkName.equals(network) && !at.isBefore(from) && !at.isAfter(until);
        }
    }

    /**
     * Opens a window in which {@code problems} are expected on the network named {@code networkName}.
     *
     * @param networkName the name of the test's target network, as it tags its log lines
     * @param problems the expected problems
     * @param from when the window opens
     * @return the window, to close when the test finishes
     */
    public static Window open(
            @NonNull final String networkName, @NonNull final List<LogProblem> problems, @NonNull final Instant from) {
        final var window = new Window(networkName, problems, from);
        WINDOWS.add(window);
        return window;
    }

    /**
     * Returns whether {@code line} is a WARN or ERROR line some test expects.
     *
     * @param line a log line
     * @return whether the line is expected
     */
    public static boolean isExpected(@NonNull final String line) {
        final var matcher = LINE.matcher(line);
        if (!matcher.find()) {
            return false;
        }
        final var at = timestampOf(matcher.group(1));
        final var network = matcher.group("network");
        if (at == null || network == null) {
            return false;
        }
        for (final var window : WINDOWS) {
            if (window.covers(network, at) && window.problems().stream().anyMatch(problem -> problem.matches(line))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the instant of a log line's timestamp, which the node writes in this machine's time zone.
     *
     * @param timestamp the line's timestamp
     * @return the instant, or {@code null} if the timestamp cannot be parsed
     */
    @Nullable
    public static Instant timestampOf(@NonNull final String timestamp) {
        try {
            return LocalDateTime.parse(timestamp, TIMESTAMP)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Returns the instant of {@code line}'s timestamp, if it starts with one.
     *
     * @param line a log line
     * @return the instant, or {@code null} if the line has no parseable timestamp
     */
    @Nullable
    public static Instant timestampOfLine(@NonNull final String line) {
        return line.length() >= 23 ? timestampOf(line.substring(0, 23)) : null;
    }
}
