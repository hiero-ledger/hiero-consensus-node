// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils;

import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.orderedRecordFilesFrom;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.parseRecordFileConsensusTime;
import static com.hedera.node.app.hapi.utils.forensics.DifferingEntries.FirstEncounteredDifference.CONSENSUS_TIME_MISMATCH;
import static com.hedera.node.app.hapi.utils.forensics.DifferingEntries.FirstEncounteredDifference.TRANSACTION_MISMATCH;
import static com.hedera.node.app.hapi.utils.forensics.DifferingEntries.FirstEncounteredDifference.TRANSACTION_RECORD_MISMATCH;
import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.findDifferencesBetweenV6;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6RecordStreamEntriesIn;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessage;
import com.hedera.node.app.hapi.utils.forensics.DifferingEntries;
import com.hedera.node.app.hapi.utils.forensics.OrderedComparison;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;

/**
 * Utility to diff two record streams, and report on the differences.
 */
public class RcDiff implements Callable<Integer> {
    private static final OrderedComparison.RecordDiffSummarizer DEFAULT_SUMMARIZER = (a, b) -> {
        try {
            exactMatch(a, b, () -> "");
        } catch (final Throwable t) {
            return t.getMessage();
        }
        throw new AssertionError("No difference to summarize");
    };

    private final Long maxDiffsToExport;

    private final Long lenOfDiffSecs;

    private final List<RecordStreamEntry> expectedStreams;
    private final List<RecordStreamEntry> actualStreams;

    private final String diffsLoc;

    private final BoundaryTimes expectedBoundaries;
    private final BoundaryTimes actualBoundaries;

    private PrintStream out = System.out;

    public RcDiff(
            final long maxDiffsToExport,
            final long lenOfDiffSecs,
            @NonNull final List<RecordStreamEntry> expectedStreams,
            @NonNull final List<RecordStreamEntry> actualStreams,
            @Nullable final String diffsLoc,
            @Nullable final PrintStream out) {
        this(
                maxDiffsToExport,
                lenOfDiffSecs,
                expectedStreams,
                actualStreams,
                diffsLoc,
                boundaryTimesFor(expectedStreams),
                boundaryTimesFor(actualStreams),
                out);
    }

    /**
     * Instantiates an {@code RcDiff} object with record files from the given directories. The object
     * returned from this method is immediately ready to compare the record files via the {@link #call()}
     * method or the {@link #summarizeDiffs()} method.
     *
     * @param maxDiffsToExport the maximum number of diffs to report in the output
     * @param lenOfDiffSecs the length, in seconds, of the diff to compute. This value is used to construct
     * a time range from the earliest record present in the expected and actual
     * streams to a caller-specified boundary point (this parameter). The earliest
     * record is computed via {@code (Math.min(expected.getFirst().consensusTimestamp(),
     * actual.getFirst().consensusTimestamp()}; the resulting time window then
     * becomes [earliestTimestamp, earliestTimestamp + lenOfDiffSeconds). All record
     * files with a starting consensus timestamp inside of this interval will be
     * included in the diff calculations, while all record files with a later timestamp
     * will be ignored. Note that this parameter <b>does not</b> override the
     * {@code maxDiffsToExport} parameter; if the number of diffs found in the computed
     * time window exceeds {@code maxDiffsToExport}, only the first
     * {@code maxDiffsToExport} diffs will be reported.
     * @param expectedStreamsLoc the location of the expected record files
     * @param actualStreamsLoc the location of the actual record files
     * @param diffsLoc an optional file location to write the diff output(s) to
     * @return the initialized {@code RcDiff} object
     * @throws IOException if there is an issue reading record files from the expected or actual
     * streams locations
     */
    public static RcDiff fromDirs(
            final long maxDiffsToExport,
            final long lenOfDiffSecs,
            @NonNull final String expectedStreamsLoc,
            @NonNull final String actualStreamsLoc,
            @NonNull final String diffsLoc)
            throws IOException {
        return new RcDiff(
                maxDiffsToExport,
                lenOfDiffSecs,
                parseV6RecordStreamEntriesIn(expectedStreamsLoc),
                parseV6RecordStreamEntriesIn(actualStreamsLoc),
                diffsLoc,
                boundaryTimesFor(expectedStreamsLoc),
                boundaryTimesFor(actualStreamsLoc),
                null);
    }

    private RcDiff(
            final long maxDiffsToExport,
            final long lenOfDiffSecs,
            @NonNull final List<RecordStreamEntry> expectedStreams,
            @NonNull final List<RecordStreamEntry> actualStreams,
            @Nullable final String diffsLoc,
            @NonNull final BoundaryTimes expectedBoundaries,
            @NonNull final BoundaryTimes actualBoundaries,
            @Nullable final PrintStream out) {
        this.maxDiffsToExport = maxDiffsToExport;
        this.lenOfDiffSecs = lenOfDiffSecs;
        this.expectedStreams = expectedStreams;
        this.actualStreams = actualStreams;
        this.diffsLoc = diffsLoc;
        this.expectedBoundaries = expectedBoundaries;
        this.actualBoundaries = actualBoundaries;
        if (out != null) {
            this.out = out;
        }

        throwOnInvalidInput();
    }

    /**
     * Given an expected and actual message, recursively asserts that they are exactly equal.
     *
     * @param expectedMessage the expected message
     * @param actualMessage the actual message
     * @param mismatchContext a supplier of a string that describes the context of the mismatch
     */
    public static void exactMatch(
            @NonNull GeneratedMessage expectedMessage,
            @NonNull GeneratedMessage actualMessage,
            @NonNull final Supplier<String> mismatchContext) {
        requireNonNull(expectedMessage);
        requireNonNull(actualMessage);
        requireNonNull(mismatchContext);
        final var expectedType = expectedMessage.getClass();
        final var actualType = actualMessage.getClass();
        if (!expectedType.equals(actualType)) {
            Assertions.fail("Mismatched types between expected " + expectedType + " and " + actualType + " - "
                    + mismatchContext.get());
        }
        // getAllFields() returns a SortedMap so ordering is deterministic here
        final var expectedFields =
                new ArrayList<>(expectedMessage.getAllFields().entrySet());
        final var actualFields = new ArrayList<>(actualMessage.getAllFields().entrySet());
        if (expectedFields.size() != actualFields.size()) {
            Assertions.fail("Mismatched field counts "
                    + " (" + describeFieldCountMismatch(expectedFields, actualFields) + ") " + "between expected "
                    + expectedMessage + " and " + actualMessage + " - " + mismatchContext.get());
        }
        for (int i = 0, n = expectedFields.size(); i < n; i++) {
            final var expectedField = expectedFields.get(i);
            final var actualField = actualFields.get(i);
            final var expectedName = expectedField.getKey().getName();
            final var actualName = actualField.getKey().getName();
            if (!Objects.equals(expectedName, actualName)) {
                Assertions.fail(
                        "Mismatched field names ('" + expectedName + "' vs '" + actualName + "' between expected "
                                + expectedMessage + " and " + actualMessage + " - " + mismatchContext.get());
            }
            matchValues(expectedName, expectedField.getValue(), actualField.getValue(), mismatchContext);
        }
    }

    /**
     * Invokes the {@link #summarizeDiffs()} method, and writes the output to both a diff file and to
     * this instance's {@code PrintStream}. This method is intended for the Services CLI tool, which
     * invokes the diffs computation via the {@code PicoCLI} library.
     *
     * @return 0 if the expected and actual record streams are identical, 1 otherwise
     * @throws Exception if any error occurs during the diff computation or output
     */
    @Override
    public Integer call() throws Exception {
        final var diffs = summarizeDiffs();
        if (diffs.isEmpty()) {
            out.println("These streams are identical ☺️");
            return 0;
        } else {
            out.println("These streams differed " + diffs.size() + " times 😞");
            dumpToFile(diffs);
            return 1;
        }
    }

    /**
     * Computes a {@code String} summary of the differences between the expected and actual record
     * streams. This method is essentially equivalent (except the output format) to the {@link #call()}
     * method, but does not write the diff output to a file.
     *
     * @return a collection of {@code DifferingEntries} objects detailing the differences between
     * the individual records of the expected and actual record streams
     */
    public List<DifferingEntries> summarizeDiffs() {
        return diffsGiven(DEFAULT_SUMMARIZER);
    }

    /**
     * Following the diff computations, this method builds a human-readable summary of said diffs. Each
     * diff represents a single computed difference between the expected record stream and the actual.
     * The output of this method is typically written to a file or printed to the console.
     *
     * @param diffs the computed diff results from {@link #summarizeDiffs()}
     * @return a human-readable summary of the diffs
     */
    public List<String> buildDiffOutput(@NonNull final List<DifferingEntries> diffs) {
        return diffs.stream().map(this::readableDiff).limit(maxDiffsToExport).toList();
    }

    private List<DifferingEntries> diffsGiven(
            @NonNull final OrderedComparison.RecordDiffSummarizer recordDiffSummarizer) {
        final var first = Collections.min(List.of(actualBoundaries.first, expectedBoundaries.first));
        final var last = Collections.max(List.of(actualBoundaries.last, expectedBoundaries.last));
        final List<DifferingEntries> diffs = new ArrayList<>();
        for (Instant i = first; !i.isAfter(last); i = i.plusSeconds(lenOfDiffSecs)) {
            final var start = i;
            final var end = i.plusSeconds(lenOfDiffSecs);
            // Include files in the range [start, end)
            final Predicate<RecordStreamEntry> inclusionTest = e -> {
                final var consensusTime = e.consensusTime();
                return !consensusTime.isBefore(start) && consensusTime.isBefore(end);
            };
            final var diffsHere =
                    findDifferencesBetweenV6(expectedStreams, actualStreams, recordDiffSummarizer, inclusionTest);

            out.println(" ➡️ Found " + diffsHere.size() + " diffs from " + start + " to " + end);
            diffs.addAll(diffsHere);
        }
        return diffs;
    }

    private record BoundaryTimes(Instant first, Instant last) {}

    private static BoundaryTimes boundaryTimesFor(@NonNull final List<RecordStreamEntry> entries) {
        if (entries.isEmpty()) {
            return new BoundaryTimes(Instant.MAX, Instant.EPOCH);
        }
        return new BoundaryTimes(
                entries.getFirst().consensusTime(), entries.getLast().consensusTime());
    }

    private static BoundaryTimes boundaryTimesFor(@NonNull final String loc) {
        try {
            final var orderedFiles = orderedRecordFilesFrom(loc, f -> true);
            if (orderedFiles.isEmpty()) {
                return new BoundaryTimes(Instant.MAX, Instant.EPOCH);
            }
            return new BoundaryTimes(
                    parseRecordFileConsensusTime(orderedFiles.getFirst()),
                    parseRecordFileConsensusTime(orderedFiles.getLast()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void throwOnInvalidInput() {
        if (actualStreams == null) {
            throw new IllegalArgumentException("Please specify a non-empty test/'actual' stream");
        }
        if (expectedStreams == null) {
            throw new IllegalArgumentException("Please specify a non-empty 'expected' stream");
        }
        if (lenOfDiffSecs <= 0) {
            throw new IllegalArgumentException("Please specify a positive length of diff in seconds");
        }
    }

    private void dumpToFile(@NonNull final List<DifferingEntries> diffs) {
        try {
            Files.write(Paths.get(diffsLoc), buildDiffOutput(diffs));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readableDiff(@NonNull final DifferingEntries diff) {
        final var firstEncounteredDifference = diff.firstEncounteredDifference();
        final var sb = new StringBuilder()
                .append("---- ")
                .append(diff.involvedFunctions())
                .append(" DIFFERED (")
                .append(firstEncounteredDifference)
                .append(") ----\n");
        if (diff.summary() != null) {
            sb.append(diff.summary()).append("\n");
        }
        if (firstEncounteredDifference == CONSENSUS_TIME_MISMATCH) {
            sb.append("➡️  Expected ")
                    .append(requireNonNull(diff.firstEntry()).consensusTime())
                    .append("\n\n")
                    .append("➡️ but was ")
                    .append(requireNonNull(diff.secondEntry()).consensusTime());
        } else if (firstEncounteredDifference == TRANSACTION_RECORD_MISMATCH
                || firstEncounteredDifference == TRANSACTION_MISMATCH) {
            sb.append("\nFor body,\n").append(requireNonNull(diff.firstEntry()).body());
            sb.append("➡️  Expected Record ")
                    .append(requireNonNull(diff.firstEntry()).transactionRecord())
                    .append(" but was ")
                    .append(requireNonNull(diff.secondEntry()).transactionRecord());
        }
        return sb.toString();
    }

    private static Set<String> fieldNamesOf(
            @NonNull final List<Map.Entry<Descriptors.FieldDescriptor, Object>> fields) {
        return fields.stream()
                .map(Map.Entry::getKey)
                .map(Descriptors.FieldDescriptor::getName)
                .collect(toSet());
    }

    /**
     * Given an expected value which may be a list, either matches all values in the list against the actual
     * value (which must of course also be a list in this case); or matches the expected single value with the
     * actual value.
     *
     * @param fieldName the name of the field being matched
     * @param expectedValue the expected value
     * @param actualValue the actual value
     * @param mismatchContext a supplier of a string that describes the context of the mismatch
     */
    private static void matchValues(
            @NonNull final String fieldName,
            @NonNull final Object expectedValue,
            @NonNull final Object actualValue,
            @NonNull final Supplier<String> mismatchContext) {
        requireNonNull(fieldName);
        requireNonNull(expectedValue);
        requireNonNull(actualValue);
        requireNonNull(mismatchContext);
        if (expectedValue instanceof List<?> expectedList) {
            if (actualValue instanceof List<?> actualList) {
                if (expectedList.size() != actualList.size()) {
                    Assertions.fail("Mismatched list sizes between expected list " + expectedList + " and " + actualList
                            + " - " + mismatchContext.get());
                }
                for (int j = 0, m = expectedList.size(); j < m; j++) {
                    final var expectedElement = expectedList.get(j);
                    final var actualElement = actualList.get(j);
                    // There are no lists of lists in the record stream, so match single values
                    matchSingleValues(expectedElement, actualElement, mismatchContext);
                }
            } else {
                Assertions.fail("Mismatched types between expected list '" + expectedList + "' and "
                        + actualValue.getClass().getSimpleName() + " '" + actualValue + "' - "
                        + mismatchContext.get());
            }
        } else {
            matchSingleValues(
                    expectedValue, actualValue, () -> "Matching field '" + fieldName + "' " + mismatchContext.get());
        }
    }

    /**
     * Either recursively matches two given {@link GeneratedMessage}; or asserts object equality via
     * {@code Assertions#assertEquals()}; or fails immediately if the types are mismatched.
     *
     * @param expected the expected value
     * @param actual the actual value
     * @param mismatchContext a supplier of a string that describes the context of the mismatch
     */
    private static void matchSingleValues(
            @NonNull final Object expected,
            @NonNull final Object actual,
            @NonNull final Supplier<String> mismatchContext) {
        requireNonNull(expected);
        requireNonNull(actual);
        requireNonNull(mismatchContext);
        if (expected instanceof GeneratedMessage expectedMessage) {
            if (actual instanceof GeneratedMessage actualMessage) {
                exactMatch(expectedMessage, actualMessage, mismatchContext);
            } else {
                Assertions.fail("Mismatched types between expected message '" + expectedMessage + "' and "
                        + actual.getClass().getSimpleName() + " '" + actual + "' - " + mismatchContext.get());
            }
        } else {
            assertEquals(
                    expected,
                    actual,
                    "Mismatched values, expected '" + expected + "', got '" + actual + "' - " + mismatchContext.get());
        }
    }

    // inline initializers
    @SuppressWarnings({"java:S3599", "java:S1171"})
    private static String describeFieldCountMismatch(
            @NonNull final List<Map.Entry<Descriptors.FieldDescriptor, Object>> expectedFields,
            @NonNull final List<Map.Entry<Descriptors.FieldDescriptor, Object>> actualFields) {
        final Set<String> expectedNames = fieldNamesOf(expectedFields);
        final Set<String> actualNames = fieldNamesOf(actualFields);
        final var expectedButNotObservedNames = new HashSet<>(expectedNames) {
            {
                removeAll(actualNames);
            }
        };
        final var observedButNotExpectedNames = new HashSet<>(actualNames) {
            {
                removeAll(expectedNames);
            }
        };
        final var description = new StringBuilder();
        if (!expectedButNotObservedNames.isEmpty()) {
            description
                    .append("expected from generated but did not find in translated ")
                    .append(expectedButNotObservedNames);
        }
        if (!observedButNotExpectedNames.isEmpty()) {
            if (!description.isEmpty()) {
                description.append(" AND ");
            }
            description.append("found in translated but not in generated ").append(observedButNotExpectedNames);
        }

        return description.toString();
    }
}
