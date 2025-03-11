// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.findDifferencesBetweenV6;
import static com.hedera.node.app.hapi.utils.forensics.OrderedComparison.statusHistograms;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6RecordStreamEntriesIn;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6SidecarRecordsByConsTimeIn;
import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.visitWithSidecars;
import static com.hedera.services.stream.proto.ContractAction.ResultDataCase.RESULTDATA_NOT_SET;
import static com.hedera.services.stream.proto.ContractAction.ResultDataCase.REVERT_REASON;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.WRONG_NONCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.stream.proto.ContractAction;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
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
    private static final TransactionRecord MOCK_RECORD = TransactionRecord.getDefaultInstance();
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
        final var parts = new TransactionParts(
                Transaction.getDefaultInstance(), TransactionBody.getDefaultInstance(), FileAppend);
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
        final var parts = new TransactionParts(
                Transaction.getDefaultInstance(), TransactionBody.getDefaultInstance(), FileAppend);
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
        final var aMockTxn = Transaction.getDefaultInstance();
        final var bMockTxn = Transaction.newBuilder()
                .setSignedTransactionBytes(ByteString.copyFromUtf8("ABCDEFG"))
                .build();
        final var aParts = new TransactionParts(aMockTxn, TransactionBody.getDefaultInstance(), FileAppend);
        final var bParts = new TransactionParts(bMockTxn, TransactionBody.getDefaultInstance(), FileAppend);
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
        assertEquals(expectedEthTxHist, histograms.get(HederaFunctionality.EthereumTransaction));

        final var fileAppends = OrderedComparison.filterByFunction(entries, FileAppend);
        assertEquals(3, fileAppends.size());
        final var appendTarget = fileAppends.get(0).body().getFileAppend().getFileID();
        assertEquals(48287857L, appendTarget.getFileNum());
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
            if (parts.function() == HederaFunctionality.EthereumTransaction) {
                final var expected = List.of(RESULTDATA_NOT_SET, REVERT_REASON);
                final var actual = records.stream()
                        .filter(TransactionSidecarRecord::hasActions)
                        .flatMap(r -> r.getActions().getContractActionsList().stream())
                        .map(ContractAction::getResultDataCase)
                        .toList();
                assertEquals(expected, actual);
            }
        });
    }
}
