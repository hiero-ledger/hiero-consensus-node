// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6RecordStreamEntriesIn;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Provides helpers to compare and analyze record streams. */
public class OrderedComparison {
    private static final Predicate<RecordStreamEntry> DEFAULT_ENTRY_INCLUSION_TEST = e -> true;
    private static final Predicate<String> DEFAULT_FILENAME_INCLUSION_TEST = filename -> true;

    private OrderedComparison() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given two directories, each containing the <b>same set</b> of compressed V6 record stream
     * files, returns a "stream diff". Each entry in the diff is a {@link DifferingEntries} object
     * that captures a single record divergence---that is, two records that had the same consensus
     * time and same source transaction in both streams, but differed in some way (e.g., their
     * receipt status).
     *
     * @param firstStreamDir the directory containing the expected record stream files
     * @param secondStreamDir the directory containing the actual record stream files
     * @param recordDiffSummarizer if present, a summarizer for record diffs
     * @param maybeInclusionTest if set, a consumer receiving the name of each file as it is parsed
     * @return the stream diff
     * @throws IllegalArgumentException if the directories contain misaligned record streams
     */
    public static List<DifferingEntries> findDifferencesBetweenV6(
            @NonNull final String firstStreamDir,
            @NonNull final String secondStreamDir,
            @Nullable final RecordDiffSummarizer recordDiffSummarizer,
            @Nullable final Predicate<String> maybeInclusionTest,
            @Nullable final String maybeInclusionDescription)
            throws IOException {
        final Predicate<String> inclusionTest =
                maybeInclusionTest == null ? DEFAULT_FILENAME_INCLUSION_TEST : maybeInclusionTest;
        final String inclusionDescription = maybeInclusionDescription == null ? "all" : maybeInclusionDescription;
        System.out.println("Parsing stream @ " + firstStreamDir + " (including " + inclusionDescription + ")");
        final var firstEntries = parseV6RecordStreamEntriesIn(firstStreamDir, inclusionTest);
        System.out.println(" ➡️  Read " + firstEntries.size() + " entries");
        System.out.println("Parsing stream @ " + secondStreamDir + " (including " + inclusionDescription + ")");
        final var secondEntries = parseV6RecordStreamEntriesIn(secondStreamDir, inclusionTest);
        System.out.println(" ➡️  Read " + secondEntries.size() + " entries");
        // No inclusion test is needed here because the inclusion test has already been applied (during parsing)
        return findDifferencesBetweenV6(firstEntries, secondEntries, recordDiffSummarizer, null);
    }

    /**
     * Like {#findDifferencesBetweenV6(String, String, RecordDiffSummarizer, Predicate, String)}, but for
     * in-memory {@link RecordStreamEntry} objects.
     *
     * @param firstEntries the first record stream (the expected records)
     * @param secondEntries the second record stream (the records being tested)
     * @param recordDiffSummarizer if present, a summarizer for record diffs
     * @param maybeInclusionTest if set, a consumer receiving the consensus timestamp of each entry
     * @return the stream diff
     * @throws IllegalArgumentException if the record streams are misaligned
     */
    public static List<DifferingEntries> findDifferencesBetweenV6(
            @NonNull final List<RecordStreamEntry> firstEntries,
            @NonNull final List<RecordStreamEntry> secondEntries,
            @Nullable final RecordDiffSummarizer recordDiffSummarizer,
            @Nullable final Predicate<RecordStreamEntry> maybeInclusionTest) {
        final var inclusionTest = maybeInclusionTest == null ? DEFAULT_ENTRY_INCLUSION_TEST : maybeInclusionTest;
        final var filteredFirst = firstEntries.stream().filter(inclusionTest).toList();
        final var filteredSecond = secondEntries.stream().filter(inclusionTest).toList();

        final var compareList = getCompareList(filteredFirst, filteredSecond);
        return diff(compareList.firstList, compareList.secondList, recordDiffSummarizer);
    }

    record CompareList(@NonNull List<RecordStreamEntry> firstList, @NonNull List<RecordStreamEntry> secondList) {}

    @NonNull
    private static CompareList getCompareList(
            List<RecordStreamEntry> firstEntries, List<RecordStreamEntry> secondEntries) {
        CompareList ret;
        final List<RecordStreamEntry> firstList = new ArrayList<>();
        final List<RecordStreamEntry> secondList = new ArrayList<>();

        if (secondEntries.isEmpty() || firstEntries.isEmpty()) {
            ret = new CompareList(firstEntries, secondEntries);
        } else {
            int firstIdx = 0;
            int secondIdx = 0;

            while (firstIdx < firstEntries.size() && secondIdx < secondEntries.size()) {
                if (firstEntries
                        .get(firstIdx)
                        .consensusTime()
                        .equals(secondEntries.get(secondIdx).consensusTime())) {
                    firstList.add(firstEntries.get(firstIdx));
                    secondList.add(secondEntries.get(secondIdx));
                    firstIdx++;
                    secondIdx++;
                } else if (firstEntries
                        .get(firstIdx)
                        .consensusTime()
                        .isBefore(secondEntries.get(secondIdx).consensusTime())) {
                    firstList.add(firstEntries.get(firstIdx));
                    secondList.add(new RecordStreamEntry(
                            null, null, firstEntries.get(firstIdx).consensusTime()));
                    firstIdx++;
                } else {
                    firstList.add(new RecordStreamEntry(
                            null, null, secondEntries.get(secondIdx).consensusTime()));
                    secondList.add(secondEntries.get(secondIdx));
                    secondIdx++;
                }
            }

            if (firstIdx < firstEntries.size()) { // j == secondEntries.size()
                for (int k = firstIdx; k < firstEntries.size(); k++) {
                    firstList.add(firstEntries.get(k));
                    secondList.add(new RecordStreamEntry(
                            null, null, firstEntries.get(k).consensusTime()));
                }
            }

            if (secondIdx < secondEntries.size()) { // i == firstEntries.size()
                for (int k = secondIdx; k < secondEntries.size(); k++) {
                    firstList.add(new RecordStreamEntry(
                            null, null, secondEntries.get(k).consensusTime()));
                    secondList.add(secondEntries.get(k));
                }
            }

            ret = new CompareList(firstList, secondList);
        }

        return ret;
    }

    public interface RecordDiffSummarizer extends BiFunction<TransactionRecord, TransactionRecord, String> {}

    private static class UnmatchableException extends Exception {
        UnmatchableException(@NonNull final String message) {
            super(message);
        }
    }

    @VisibleForTesting
    static List<DifferingEntries> diff(
            @NonNull final List<RecordStreamEntry> firstEntries,
            @NonNull final List<RecordStreamEntry> secondEntries,
            @Nullable final RecordDiffSummarizer recordDiffSummarizer) {
        requireNonNull(firstEntries);
        requireNonNull(secondEntries);
        final List<DifferingEntries> diffs = new ArrayList<>();
        final var minSize = Math.min(firstEntries.size(), secondEntries.size());
        for (int i = 0; i < minSize; i++) {
            final var firstEntry = firstEntries.get(i);
            try {
                if (secondEntries.get(i).txnRecord() == null) {
                    diffs.add(new DifferingEntries(
                            firstEntry,
                            null,
                            "No first/test stream record found at " + firstEntry.consensusTime()
                                    + " for transactionID : "
                                    + firstEntry.txnRecord().getTransactionID() + " transBody : " + firstEntry.body()));
                    continue;
                }
                if (firstEntries.get(i).txnRecord() == null) {
                    diffs.add(new DifferingEntries(
                            null,
                            secondEntries.get(i),
                            "Additional first/test stream record found at "
                                    + secondEntries.get(i).consensusTime() + " for transactionID : "
                                    + secondEntries.get(i).txnRecord().getTransactionID() + " transBody : "
                                    + secondEntries.get(i).body() + "\n -> \n"
                                    + secondEntries.get(i).txnRecord()));
                    continue;
                }
                final var secondEntry = entryWithMatchableRecord(secondEntries, i, firstEntry);
                if (!firstEntry.txnRecord().equals(secondEntry.txnRecord())) {
                    final var summary = recordDiffSummarizer == null
                            ? null
                            : recordDiffSummarizer.apply(firstEntry.txnRecord(), secondEntry.txnRecord());
                    diffs.add(new DifferingEntries(firstEntry, secondEntry, summary));
                }
            } catch (UnmatchableException e) {
                diffs.add(new DifferingEntries(firstEntry, secondEntries.get(i), e.getMessage()));
            }
        }
        if (firstEntries.size() != secondEntries.size()) {
            appendExtraEntriesFrom(firstEntries, secondEntries, minSize, diffs);
        }
        return diffs;
    }

    /**
     * Given a list of record stream entries, returns a map that, for each {@link
     * HederaFunctionality} value, includes the counts of all {@link ResponseCodeEnum} values that
     * appeared in the record stream.
     *
     * @param entries record stream entries
     * @return a "histogram" of resolved statuses
     */
    public static Map<HederaFunctionality, Map<ResponseCodeEnum, Integer>> statusHistograms(
            final List<RecordStreamEntry> entries) {
        final Map<HederaFunctionality, Map<ResponseCodeEnum, Integer>> counts =
                new EnumMap<>(HederaFunctionality.class);
        for (final var entry : entries) {
            counts.computeIfAbsent(entry.parts().function(), ignore -> new EnumMap<>(ResponseCodeEnum.class))
                    .merge(entry.finalStatus(), 1, Integer::sum);
        }
        return counts;
    }

    public static List<RecordStreamEntry> filterByFunction(
            final List<RecordStreamEntry> entries, final HederaFunctionality function) {
        return entries.stream().filter(entry -> entry.function() == function).toList();
    }

    /**
     * Assuming the given list of entries has a "matchable" entry at the given
     * index, returns that entry. Throws an exception if the entry at the given
     * index is not matchable; i.e., if it has a different consensus time or
     * transaction than the entry at the given index.
     *
     * @param entries a list of entries
     * @param index the index of the entry to match
     * @param entryToMatch the entry to match
     * @return the entry at the given index
     */
    @NonNull
    private static RecordStreamEntry entryWithMatchableRecord(
            @NonNull final List<RecordStreamEntry> entries,
            final int index,
            @NonNull final RecordStreamEntry entryToMatch)
            throws UnmatchableException {
        final var secondEntry = entries.get(index);
        if (!entryToMatch.consensusTime().equals(secondEntry.consensusTime())) {
            throw new UnmatchableException("Entries at position "
                    + index
                    + " had different consensus times ("
                    + entryToMatch.consensusTime()
                    + " vs "
                    + secondEntry.consensusTime()
                    + ")");
        }
        if (!entryToMatch.submittedTransaction().equals(secondEntry.submittedTransaction())) {
            throw new UnmatchableException("Entries at position "
                    + index
                    + " had different transactions ("
                    + entryToMatch.submittedTransaction()
                    + " vs "
                    + secondEntry.submittedTransaction()
                    + ")");
        }
        return secondEntry;
    }

    private static void appendExtraEntriesFrom(
            @NonNull final List<RecordStreamEntry> firstEntries,
            @NonNull final List<RecordStreamEntry> secondEntries,
            final int minSize,
            @NonNull final List<DifferingEntries> diffs) {
        final var firstHasExtra = firstEntries.size() > secondEntries.size();
        final var extraEntries = firstHasExtra
                ? firstEntries.subList(minSize, firstEntries.size())
                : secondEntries.subList(minSize, secondEntries.size());
        final var maxSize = Math.max(firstEntries.size(), secondEntries.size());
        for (var i = minSize; i < maxSize; i++) {
            final var summary = firstHasExtra
                    ? "Extra entry at index " + i + " from first stream"
                    : "Extra entry at index " + i + " from second stream";
            final var extraEntry = extraEntries.get(i - minSize);
            diffs.add(new DifferingEntries(
                    firstHasExtra ? extraEntry : null, firstHasExtra ? null : extraEntry, summary));
        }
    }
}
