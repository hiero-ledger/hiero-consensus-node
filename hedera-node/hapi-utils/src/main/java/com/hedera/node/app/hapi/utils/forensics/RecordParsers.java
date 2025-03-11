// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.CommonUtils.timestampToInstant;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.readMaybeCompressedRecordStreamFile;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.readSidecarFile;

import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Provides a helper to parse the <i>.rcd.gz</i> files in a directory into a list of {@link
 * RecordStreamEntry} objects.
 *
 * <p><b>NOTE:</b> This class is only for offline analysis and debugging; it is not used at node
 * runtime.
 */
public class RecordParsers {

    private RecordParsers() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given a directory of compressed V6 record files, returns a list of all the {@code
     * (Transaction, TransactionRecord)} entries contained in those files, in order of ascending
     * consensus time.
     *
     * @param streamDir a directory with compressed V6 record files
     * @return all the contained stream entries
     * @throws IOException if the files cannot be read or parsed
     */
    @SuppressWarnings("java:S3655")
    public static List<RecordStreamEntry> parseV6RecordStreamEntriesIn(final String streamDir) throws IOException {
        return parseV6RecordStreamEntriesIn(streamDir, f -> true);
    }

    /**
     * Given a directory of compressed V6 record files, and a predicate testing whether a particular
     * file is of interest, returns a list of all the {@code (Transaction, TransactionRecord)} entries
     * contained in those files, in order of ascending consensus time.
     *
     * @param streamDir a directory with compressed V6 record files
     * @return all the contained stream entries
     * @throws IOException if the files cannot be read or parsed
     */
    public static List<RecordStreamEntry> parseV6RecordStreamEntriesIn(
            final String streamDir, final Predicate<String> inclusionTest) throws IOException {
        final var recordFiles = RecordStreamingUtils.orderedRecordFilesFrom(streamDir, inclusionTest);
        final List<RecordStreamEntry> entries = new ArrayList<>();
        for (final var recordFile : recordFiles) {
            readMaybeCompressedRecordStreamFile(recordFile)
                    .getValue()
                    .ifPresent(records -> records.getRecordStreamItemsList().forEach(item -> {
                        final var itemRecord = item.getRecord();
                        entries.add(new RecordStreamEntry(
                                TransactionParts.from(item.getTransaction()),
                                itemRecord,
                                timestampToInstant(itemRecord.getConsensusTimestamp())));
                    }));
        }
        return entries;
    }

    /**
     * Given the location of a directory structure that includes compressed V6 sidecar files,
     * returns a map from consensus time to the sidecar entries found for that consensus time.
     *
     * <p>The map can then be given to {@link RecordParsers#visitWithSidecars(List, Map,
     * BiConsumer)} to investigate the record stream with sidecar information.
     *
     * @param streamDir a directory with compressed V6 sidecar files
     * @return the map from consensus time to attached sidecars
     * @throws IOException if the files cannot be read or parsed
     */
    @SuppressWarnings("java:S3655")
    public static Map<Instant, List<TransactionSidecarRecord>> parseV6SidecarRecordsByConsTimeIn(final String streamDir)
            throws IOException {
        final var sidecarFiles = RecordStreamingUtils.orderedSidecarFilesFrom(streamDir);
        final Map<Instant, List<TransactionSidecarRecord>> sidecarRecords = new HashMap<>();
        for (final var sidecarFile : sidecarFiles) {
            final var data = readSidecarFile(sidecarFile);
            data.getSidecarRecordsList().forEach(sidecarRecord -> sidecarRecords
                    .computeIfAbsent(
                            timestampToInstant(sidecarRecord.getConsensusTimestamp()), ignore -> new ArrayList<>())
                    .add(sidecarRecord));
        }
        return sidecarRecords;
    }

    public static void visitWithSidecars(
            final List<RecordStreamEntry> entries,
            final Map<Instant, List<TransactionSidecarRecord>> sidecarRecords,
            final BiConsumer<RecordStreamEntry, List<TransactionSidecarRecord>> observer) {
        entries.forEach(entry ->
                observer.accept(entry, sidecarRecords.getOrDefault(entry.consensusTime(), Collections.emptyList())));
    }
}
