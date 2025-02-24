// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WRONG_NONCE;
import static com.hedera.hapi.streams.ContractAction.ResultDataOneOfType.REVERT_REASON;
import static com.hedera.hapi.streams.ContractAction.ResultDataOneOfType.UNSET;
import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.findDifferencesBetweenV6;
import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.statusHistograms;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6RecordStreamEntriesIn;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6SidecarRecordsByConsTimeIn;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.visitWithSidecars;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderedComparisonTest {
    private static final Path WRONG_NONCE_STREAMS_DIR =
            Paths.get("src", "test", "resources", "forensics", "CaseOfTheObviouslyWrongNonce");
    private static final Path ABSENT_RESULT_STREAMS_DIR =
            Paths.get("src", "test", "resources", "forensics", "CaseOfTheAbsentResult");
    private static final TransactionRecord MOCK_RECORD = TransactionRecord.DEFAULT;
    private static final Instant THEN = Instant.ofEpochSecond(1_234_567, 890);
    private static final Instant NOW = Instant.ofEpochSecond(9_999_999, 001);

    @Test
    void detectsDifferenceInCaseOfObviouslyWrongNonce() throws IOException {
        final var issStreamLoc = WRONG_NONCE_STREAMS_DIR + File.separator + "node5";
        final var consensusStreamLoc = WRONG_NONCE_STREAMS_DIR + File.separator + "node0";

        final var issStream = parseV6RecordStreamEntriesIn(issStreamLoc);
        final var consensusStream = parseV6RecordStreamEntriesIn(consensusStreamLoc);
        final var diffs = findDifferencesBetweenV6(issStream, consensusStream, null, null);
        assertEquals(1, diffs.size());
        final var soleDiff = diffs.get(0);
        final var issEntry = soleDiff.firstEntry();
        final var consensusEntry = soleDiff.secondEntry();

        final var issResolvedStatus = issEntry.finalStatus();
        final var consensusResolvedStatus = consensusEntry.finalStatus();
        assertEquals(INVALID_ACCOUNT_ID, issResolvedStatus);
        assertEquals(WRONG_NONCE, consensusResolvedStatus);
    }

    @Test
    void onlyEqualLengthsCanBeDiffed() {
        final var parts = new TransactionParts(Transaction.DEFAULT, TransactionBody.DEFAULT, FILE_APPEND);
        final var aEntry = new RecordStreamEntry(parts, MOCK_RECORD, NOW);
        final var firstList = Collections.<RecordStreamEntry>emptyList();
        final var secondList = List.of(aEntry);
        final var diffs = OrderedComparison.diff(firstList, secondList, null);
        assertEquals(1, diffs.size());
        final var soleDiff = diffs.get(0);
        assertEquals(aEntry, soleDiff.secondEntry());
        assertNull(soleDiff.firstEntry());
    }

    @Test
    void allTimestampsMustMatch() {
        final var parts = new TransactionParts(Transaction.DEFAULT, TransactionBody.DEFAULT, FILE_APPEND);
        final var aEntry = new RecordStreamEntry(parts, MOCK_RECORD, THEN);
        final var bEntry = new RecordStreamEntry(parts, MOCK_RECORD, NOW);
        final var firstList = List.of(aEntry);
        final var secondList = List.of(bEntry);
        final var diffs = OrderedComparison.diff(firstList, secondList, null);
        assertEquals(1, diffs.size());
        final var soleDiff = diffs.get(0);
        assertEquals(aEntry, soleDiff.firstEntry());
        assertEquals(bEntry, soleDiff.secondEntry());
    }

    @Test
    void allTransactionsMustMatch() {
        final var aMockTxn = Transaction.DEFAULT;
        final var bMockTxn = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.wrap("ABCDEFG"))
                .build();
        final var aParts = new TransactionParts(aMockTxn, TransactionBody.DEFAULT, FILE_APPEND);
        final var bParts = new TransactionParts(bMockTxn, TransactionBody.DEFAULT, FILE_APPEND);
        final var aEntry = new RecordStreamEntry(aParts, MOCK_RECORD, THEN);
        final var bEntry = new RecordStreamEntry(bParts, MOCK_RECORD, THEN);
        final var firstList = List.of(aEntry);
        final var secondList = List.of(bEntry);
        final var diffs = OrderedComparison.diff(firstList, secondList, null);
        assertEquals(1, diffs.size());
        final var soleDiff = diffs.get(0);
        assertEquals(aEntry, soleDiff.firstEntry());
        assertEquals(bEntry, soleDiff.secondEntry());
    }

    @Test
    void auxInvestigationMethodsWork() throws IOException {
        final var issStreamLoc = WRONG_NONCE_STREAMS_DIR + File.separator + "node5";
        final var entries = parseV6RecordStreamEntriesIn(issStreamLoc);

        final var histograms = statusHistograms(entries);
        final var expectedEthTxHist = Map.of(INVALID_ACCOUNT_ID, 1);
        assertEquals(expectedEthTxHist, histograms.get(ETHEREUM_TRANSACTION));

        final var fileAppends = OrderedComparison.filterByFunction(entries, FILE_APPEND);
        assertEquals(3, fileAppends.size());
        final var appendTarget = fileAppends.get(0).body().fileAppend().fileID();
        assertEquals(48287857L, appendTarget.fileNum());
    }

    @Test
    void canInvestigateWithCorrelatedSidecars() throws IOException {
        final var loc = ABSENT_RESULT_STREAMS_DIR + File.separator + "node0";
        final var entries = parseV6RecordStreamEntriesIn(loc);
        final var firstEntryRepr = entries.get(0).toString();
        assertTrue(firstEntryRepr.startsWith("RecordStreamEntry{consensusTime=2022-12-05T14:23:46.192841556Z"));
        final var sidecarRecords = parseV6SidecarRecordsByConsTimeIn(loc);

        visitWithSidecars(entries, sidecarRecords, (entry, records) -> {
            final var parts = entry.parts();
            if (parts.function() == ETHEREUM_TRANSACTION) {
                final var expected = List.of(UNSET, REVERT_REASON);
                final var actual = records.stream()
                        .filter(TransactionSidecarRecord::hasActions)
                        .flatMap(r -> r.actions().contractActions().stream())
                        .map(ContractAction::resultData)
                        .map(OneOf::kind)
                        .toList();
                assertEquals(expected, actual);
            }
        });
    }
}
