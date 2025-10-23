// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.forensics.DifferingEntries;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.hapi.utils.forensics.TransactionParts;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.utils.RcDiff;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     *
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
        final var roleFreeSplit = new RoleFreeBlockUnitSplit();
        final var roleFreeRecords = blocks.stream()
                .flatMap(block ->
                        roleFreeSplit.split(block).stream().map(BlockTransactionalUnit::withBatchTransactionParts))
                .peek(unit -> numStateChanges.getAndAdd(unit.stateChanges().size()))
                .flatMap(unit -> rfTranslator.translate(unit).stream())
                .toList();
        final var actualEntries = roleFreeRecords.stream().map(this::asEntry).toList();
        final var roleFreeDiff = new RcDiff(
                MAX_DIFFS_TO_REPORT, DIFF_INTERVAL_SECONDS, expectedEntries, actualEntries, null, System.out);
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
            logger.info("Role-free validation complete. Summary: {}", rfValidatorSummary);
        } else {
            final var diffOutput = roleFreeDiff.buildDiffOutput(roleFreeDiffs);
            final var errorMsg = new StringBuilder()
                    .append(diffOutput.size())
                    .append(" differences found between generated and translated records");
            diffOutput.forEach(summary -> errorMsg.append("\n\n").append(summary));
            Assertions.fail(errorMsg.toString());
        }

        final List<TransactionSidecarRecord> expectedSidecars = data.records().stream()
                .flatMap(recordWithSidecars ->
                        recordWithSidecars.sidecarFiles().stream().flatMap(f -> f.getSidecarRecordsList().stream()))
                .toList();
        List<TransactionSidecarRecord> actualSidecars = roleFreeRecords.stream()
                .flatMap(r -> r.transactionSidecarRecords().stream())
                .map(r -> pbjToProto(
                        r, com.hedera.hapi.streams.TransactionSidecarRecord.class, TransactionSidecarRecord.class))
                .toList();
        final Set<Timestamp> times = new HashSet<>();
        final Set<Timestamp> duplicates = new HashSet<>();
        for (final var sidecar : actualSidecars) {
            if (sidecar.hasBytecode()) {
                final var consensusTimestamp = sidecar.getConsensusTimestamp();
                if (!times.add(consensusTimestamp)) {
                    duplicates.add(consensusTimestamp);
                }
            }
        }
        if (!duplicates.isEmpty()) {
            actualSidecars = actualSidecars.stream()
                    .filter(sidecar -> !sidecar.hasBytecode() || !duplicates.remove(sidecar.getConsensusTimestamp()))
                    .toList();
        }
        if (expectedSidecars.size() != actualSidecars.size()) {
            final var expectedMap = byTime(expectedSidecars);
            final var actualMap = byTime(actualSidecars);
            expectedMap.entrySet().forEach(entry -> {
                final var consensusTimestamp = entry.getKey();
                if (!actualMap.containsKey(consensusTimestamp)) {
                    logger.error(
                            "Expected sidecar {} missing for timestamp",
                            readableBytecodesFrom(expectedMap.get(consensusTimestamp)));
                } else if (!entry.getValue().equals(actualMap.get(consensusTimestamp))) {
                    logger.error(
                            "Mismatch in sidecar for timestamp {}: expected {}, found {}",
                            consensusTimestamp,
                            readableBytecodesFrom(entry.getValue()),
                            readableBytecodesFrom(actualMap.get(consensusTimestamp)));
                }
            });
            Assertions.fail("Mismatch in number of sidecars - expected " + typeHistogramOf(expectedSidecars)
                    + ", found " + typeHistogramOf(actualSidecars));
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

    private Map<Timestamp, List<TransactionSidecarRecord>> byTime(
            @NonNull final List<TransactionSidecarRecord> sidecars) {
        requireNonNull(sidecars);
        return sidecars.stream().collect(groupingBy(TransactionSidecarRecord::getConsensusTimestamp, toList()));
    }

    private String typeHistogramOf(List<TransactionSidecarRecord> r) {
        return r.stream()
                .collect(groupingBy(TransactionSidecarRecord::getSidecarRecordsCase, counting()))
                .toString();
    }

    private String readableBytecodesFrom(@NonNull final List<TransactionSidecarRecord> sidecars) {
        return sidecars.stream().map(this::readableBytecodeFrom).toList().toString();
    }

    private String readableBytecodeFrom(@NonNull final TransactionSidecarRecord sidecar) {
        if (sidecar.hasBytecode()) {
            final var at = sidecar.getConsensusTimestamp();
            return "@ " + at.getSeconds() + "." + at.getNanos() + " for #"
                    + sidecar.getBytecode().getContractId().getContractNum() + " (has initcode? "
                    + !sidecar.getBytecode().getInitcode().isEmpty() + ") " + " (has runtime bytecode? "
                    + !sidecar.getBytecode().getRuntimeBytecode().isEmpty();
        } else {
            return "<N/A>";
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
