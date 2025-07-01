// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.hapi.utils.forensics.DifferingEntries;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.hapi.utils.forensics.TransactionParts;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockUnitSplit;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.utils.RcDiff;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that asserts the block stream contains all information previously exported in the record stream
 * by translating the block stream into transaction records and comparing them to the expected records.
 */
public class TransactionRecordParityValidator implements BlockStreamValidator {
    private static final int MAX_DIFFS_TO_REPORT = 10;
    private static final int DIFF_INTERVAL_SECONDS = 300;
    private static final Logger logger = LogManager.getLogger(TransactionRecordParityValidator.class);

    private final BlockUnitSplit blockUnitSplit = new BlockUnitSplit();
    private final BlockTransactionalUnitTranslator translator;

    public static final Factory FACTORY = new Factory() {
        @Override
        public boolean appliesTo(@NonNull final HapiSpec spec) {
            requireNonNull(spec);
            // Embedded networks don't have saved states or a Merkle tree to validate hashes against
            return spec.targetNetworkOrThrow().type() == SUBPROCESS_NETWORK;
        }

        @Override
        public @NonNull TransactionRecordParityValidator create(@NonNull final HapiSpec spec) {
            return new TransactionRecordParityValidator();
        }
    };

    public TransactionRecordParityValidator() {
        translator = new BlockTransactionalUnitTranslator();
    }

    /**
     * A main method to run a standalone validation of the block stream against the record stream in this project.
     * @param args unused
     * @throws IOException if there is an error reading the block or record streams
     */
    public static void main(@NonNull final String[] args) throws IOException {
        final var node0Data = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi").resolve("data"))
                .toAbsolutePath()
                .normalize();
        final var blocksLoc =
                node0Data.resolve("blockStreams/block-11.12.3").toAbsolutePath().normalize();
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blocksLoc);
        final var recordsLoc = node0Data
                .resolve("recordStreams/record11.12.3")
                .toAbsolutePath()
                .normalize();
        final var records = StreamFileAccess.STREAM_FILE_ACCESS.readStreamDataFrom(recordsLoc.toString(), "sidecar");

        final var validator = new TransactionRecordParityValidator();
        validator.validateBlockVsRecords(blocks, records);
    }

    @Override
    public void validateBlockVsRecords(
            @NonNull final List<Block> blocks, @NonNull final StreamFileAccess.RecordStreamData data) {
        requireNonNull(blocks);
        requireNonNull(data);

        final var rfTranslator = new BlockTransactionalUnitTranslator();
        var foundGenesisBlock = false;
        for (final var block : blocks) {
            if (translator.scanBlockForGenesis(block)) {
                rfTranslator.scanBlockForGenesis(block);
                foundGenesisBlock = true;
                break;
            }
        }
        if (!foundGenesisBlock) {
            logger.error("Genesis block not found in block stream, at least some receipts will not match");
        }
        final var expectedEntries = data.records().stream()
                .flatMap(recordWithSidecars -> recordWithSidecars.recordFile().getRecordStreamItemsList().stream())
                .map(RecordStreamEntry::from)
                .toList();
        final var numStateChanges = new AtomicInteger();
        final var actualUnits = blocks.stream()
                .flatMap(block ->
                        blockUnitSplit.split(block).stream().map(BlockTransactionalUnit::withBatchTransactionParts))
                .toList();
        final List<SingleTransactionRecord> actualSingleTransactionRecords = blocks.stream()
                .flatMap(block ->
                        blockUnitSplit.split(block).stream().map(BlockTransactionalUnit::withBatchTransactionParts))
                .peek(unit -> numStateChanges.getAndAdd(unit.stateChanges().size()))
                .flatMap(unit -> translator.translate(unit).stream())
                .toList();
        final List<RecordStreamEntry> actualEntries =
                actualSingleTransactionRecords.stream().map(this::asEntry).toList();

        // <TEMP>
        final var roleFreeSplit = new RoleFreeBlockUnitSplit();
        final var rfUnits = blocks.stream()
                .flatMap(block ->
                        roleFreeSplit.split(block).stream().map(BlockTransactionalUnit::withBatchTransactionParts))
                .toList();
        //        assertEquals(actualUnits.size(), rfUnits.size());
        final int m = Math.min(actualEntries.size(), rfUnits.size());
        for (int i = 0; i < m; i++) {
            final var actual = actualUnits.get(i);
            final var roleFree = rfUnits.get(i);
            System.out.println("--- Index " + i + " ---");
            //            assertEquals(actual, roleFree, "Units at index " + i + " differ");
            //            assertEquals(actual.stateChanges().size(), roleFree.stateChanges().size(), "Wrong number of
            // state changes in unit " + i);
            assertEquals(
                    actual.blockTransactionParts().size(),
                    roleFree.blockTransactionParts().size(),
                    "Wrong number of transaction parts in unit " + i + " expected - \n\n"
                            + actual.blockTransactionParts() + " but was - \n\n" + roleFree.blockTransactionParts());
            for (int j = 0, n = actual.blockTransactionParts().size(); j < n; j++) {
                final var actualPart = actual.blockTransactionParts().get(j);
                final var roleFreePart = roleFree.blockTransactionParts().get(j);
                assertEquals(
                        actualPart.transactionParts(),
                        roleFreePart.transactionParts(),
                        "Transaction parts at index " + j + " differ in unit " + i);
                assertEquals(
                        actualPart.functionality(),
                        roleFreePart.functionality(),
                        "Functionality at index " + j + " differs in unit " + i);
            }
            System.out.println(
                    " - " + actual.blockTransactionParts().size() + " BlockTransactionParts in unit " + i + " matched");
        }
        //        assertEquals(actualEntries.size(), roleFreeEntries.size());
        //        for (int i = 0, n = actualEntries.size(); i < n; i++) {
        //            final var actual = actualEntries.get(i);
        //            final var roleFree = roleFreeEntries.get(i);
        //        }
        final var roleFreeRecords = blocks.stream()
                .flatMap(block ->
                        roleFreeSplit.split(block).stream().map(BlockTransactionalUnit::withBatchTransactionParts))
                .flatMap(unit -> rfTranslator.translate(unit).stream())
                .toList();
        final var roleFreeEntries = roleFreeRecords.stream().map(this::asEntry).toList();
        final var roleFreeDiff = new RcDiff(
                MAX_DIFFS_TO_REPORT, DIFF_INTERVAL_SECONDS, actualEntries, roleFreeEntries, null, System.out);
        final var roleFreeDiffs = roleFreeDiff.summarizeDiffs();
        final var rfValidatorSummary = new SummaryBuilder(
                        MAX_DIFFS_TO_REPORT,
                        DIFF_INTERVAL_SECONDS,
                        blocks.size(),
                        expectedEntries.size(),
                        actualEntries.size(),
                        numStateChanges.get(),
                        roleFreeDiffs)
                .build();
        if (roleFreeDiffs.isEmpty()) {
            logger.info("Validation complete. Summary: {}", rfValidatorSummary);
        } else {
            final var diffOutput = roleFreeDiff.buildDiffOutput(roleFreeDiffs);
            final var errorMsg = new StringBuilder()
                    .append(diffOutput.size())
                    .append(" differences found between role-based and role-free records");
            diffOutput.forEach(summary -> errorMsg.append("\n\n").append(summary));
            Assertions.fail(errorMsg.toString());
        }
        // </TEMP>

        final var rcDiff = new RcDiff(
                MAX_DIFFS_TO_REPORT, DIFF_INTERVAL_SECONDS, expectedEntries, actualEntries, null, System.out);
        final var diffs = rcDiff.summarizeDiffs();
        final var validatorSummary = new SummaryBuilder(
                        MAX_DIFFS_TO_REPORT,
                        DIFF_INTERVAL_SECONDS,
                        blocks.size(),
                        expectedEntries.size(),
                        actualEntries.size(),
                        numStateChanges.get(),
                        diffs)
                .build();
        if (diffs.isEmpty()) {
            logger.info("Validation complete. Summary: {}", validatorSummary);
        } else {
            final var diffOutput = rcDiff.buildDiffOutput(diffs);
            final var errorMsg = new StringBuilder()
                    .append(diffOutput.size())
                    .append(" differences found between translated and expected records");
            diffOutput.forEach(summary -> errorMsg.append("\n\n").append(summary));
            Assertions.fail(errorMsg.toString());
        }

        final List<TransactionSidecarRecord> expectedSidecars = data.records().stream()
                .flatMap(recordWithSidecars ->
                        recordWithSidecars.sidecarFiles().stream().flatMap(f -> f.getSidecarRecordsList().stream()))
                .toList();
        final List<TransactionSidecarRecord> actualSidecars = actualSingleTransactionRecords.stream()
                .flatMap(r -> r.transactionSidecarRecords().stream())
                .map(r -> pbjToProto(
                        r, com.hedera.hapi.streams.TransactionSidecarRecord.class, TransactionSidecarRecord.class))
                .toList();
        if (expectedSidecars.size() != actualSidecars.size()) {
            Assertions.fail("Mismatch in number of sidecars - expected " + expectedSidecars.size() + ", found "
                    + actualSidecars.size());
        } else {
            for (int i = 0, n = expectedSidecars.size(); i < n; i++) {
                final var expected = expectedSidecars.get(i);
                final var actual = actualSidecars.get(i);
                if (!expected.equals(actual)) {
                    Assertions.fail(
                            "Mismatch in sidecar at index " + i + ": expected\n" + expected + "\n, found " + actual);
                }
            }
        }
    }

    private RecordStreamEntry asEntry(@NonNull final SingleTransactionRecord record) {
        final var parts = TransactionParts.from(fromPbj(record.transaction()));
        final var consensusTimestamp = record.transactionRecord().consensusTimestampOrThrow();
        return new RecordStreamEntry(
                parts,
                pbjToProto(
                        record.transactionRecord(),
                        TransactionRecord.class,
                        com.hederahashgraph.api.proto.java.TransactionRecord.class),
                Instant.ofEpochSecond(consensusTimestamp.seconds(), consensusTimestamp.nanos()));
    }

    private record SummaryBuilder(
            int maxDiffs,
            int lenOfDiffSecs,
            int numParsedBlockItems,
            int numExpectedRecords,
            int numInputTxns,
            int numStateChanges,
            List<DifferingEntries> result) {
        String build() {
            final var summary = new StringBuilder("\n")
                    .append("Max diffs used: ")
                    .append(maxDiffs)
                    .append("\n")
                    .append("Length of diff seconds used: ")
                    .append(lenOfDiffSecs)
                    .append("\n")
                    .append("Number of block items processed: ")
                    .append(numParsedBlockItems)
                    .append("\n")
                    .append("Number of record items processed: ")
                    .append(numExpectedRecords)
                    .append("\n")
                    .append("Number of (non-null) transaction items processed: ")
                    .append(numInputTxns)
                    .append("\n")
                    .append("Number of state changes processed: ")
                    .append(numStateChanges)
                    .append("\n")
                    .append("Number of errors: ")
                    .append(result.size()); // Report the count of errors (if any)

            return summary.toString();
        }
    }
}
