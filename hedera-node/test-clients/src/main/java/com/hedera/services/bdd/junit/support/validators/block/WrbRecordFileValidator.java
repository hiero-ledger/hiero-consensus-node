// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Validates that each WRB (Wrapped Record Block) block's
 * contents — both the {@code RecordStreamFile} and sidecar files — match the corresponding files written to disk
 * by the node, matched by block number.
 */
public class WrbRecordFileValidator implements BlockStreamValidator {
    private static final Logger log = LogManager.getLogger(WrbRecordFileValidator.class);

    public static final Factory FACTORY = new Factory() {
        @Override
        public @NonNull BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return new WrbRecordFileValidator();
        }
    };

    @Override
    public void validateBlockVsRecords(
            @NonNull final List<Block> blocks, @NonNull final StreamFileAccess.RecordStreamData data) {
        final var wrbBlocks = blocks.stream()
                .filter(b -> BlockStreamValidator.isWrappedRecordBlock(b.items()))
                .sorted(Comparator.comparingLong(WrbRecordFileValidator::blockNumberOf))
                .toList();

        if (wrbBlocks.isEmpty()) {
            log.info("No WRB blocks found; skipping WRB record file validation");
            return;
        }

        // Build maps keyed by block number from the disk data.
        // data.files() / data.records() contain protobuf-java types. We normalize both sides through
        // PBJ serialization to eliminate any cross-serializer encoding differences.
        final Map<Long, Bytes> diskRecordFileByBlockNumber = new HashMap<>();
        for (final var diskFile : data.files()) {
            final long blockNum = diskFile.getBlockNumber();
            try {
                final var pbjRsf = RecordStreamFile.PROTOBUF.parse(Bytes.wrap(diskFile.toByteArray()));
                diskRecordFileByBlockNumber.put(blockNum, RecordStreamFile.PROTOBUF.toBytes(pbjRsf));
            } catch (final Exception e) {
                log.warn("Failed to normalize disk record file for block {}; skipping", blockNum, e);
            }
        }

        final Map<Long, List<Bytes>> diskSidecarsByBlockNumber = new HashMap<>();
        for (final RecordWithSidecars record : data.records()) {
            final long blockNum = record.recordFile().getBlockNumber();
            final List<Bytes> normalizedSidecars = record.sidecarFiles().stream()
                    .map(diskSidecar -> {
                        try {
                            final var pbjSidecar = SidecarFile.PROTOBUF.parse(Bytes.wrap(diskSidecar.toByteArray()));
                            return SidecarFile.PROTOBUF.toBytes(pbjSidecar);
                        } catch (final Exception e) {
                            log.warn("Failed to normalize disk sidecar for block {}; using empty bytes", blockNum, e);
                            return Bytes.EMPTY;
                        }
                    })
                    .toList();
            diskSidecarsByBlockNumber.put(blockNum, normalizedSidecars);
        }

        log.info(
                "Validating {} WRB blocks against {} disk record files and {} disk sidecar sets (matched by block number)",
                wrbBlocks.size(),
                diskRecordFileByBlockNumber.size(),
                diskSidecarsByBlockNumber.size());

        for (final var block : wrbBlocks) {
            final long headerBlockNumber = blockNumberOf(block);
            final var recordFileItem = block.items().stream()
                    .filter(BlockItem::hasRecordFile)
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalStateException("WRB block " + headerBlockNumber + " has no RecordFileItem"))
                    .recordFile();

            final var wrbRecordFile = recordFileItem.recordFileContents();
            final long wrbBlockNumber = wrbRecordFile.blockNumber();

            // Verify the block number inside the RecordStreamFile matches the block header
            if (wrbBlockNumber != headerBlockNumber) {
                Assertions.fail(String.format(
                        "Block %d: RecordFileItem.recordFileContents().blockNumber() is %d but block header says %d",
                        headerBlockNumber, wrbBlockNumber, headerBlockNumber));
                continue;
            }

            validateRecordFile(headerBlockNumber, wrbRecordFile, diskRecordFileByBlockNumber);
            validateSidecars(headerBlockNumber, recordFileItem.sidecarFileContents(), diskSidecarsByBlockNumber);
        }
    }

    private static void validateRecordFile(
            final long blockNumber,
            @NonNull final RecordStreamFile wrbRecordFile,
            @NonNull final Map<Long, Bytes> diskRecordFileByBlockNumber) {
        final var diskBytes = diskRecordFileByBlockNumber.get(blockNumber);
        if (diskBytes == null) {
            Assertions.fail("Block " + blockNumber + ": no matching disk record file found");
            return;
        }
        final var wrbBytes = RecordStreamFile.PROTOBUF.toBytes(wrbRecordFile);
        if (!wrbBytes.equals(diskBytes)) {
            Assertions.fail(String.format(
                    "Block %d: WRB record file bytes do not match disk record file (%d vs %d bytes)",
                    blockNumber, wrbBytes.length(), diskBytes.length()));
        } else {
            log.info("Block {}: WRB record file matches disk ({} bytes)", blockNumber, wrbBytes.length());
        }
    }

    private static void validateSidecars(
            final long blockNumber,
            @NonNull final List<SidecarFile> wrbSidecars,
            @NonNull final Map<Long, List<Bytes>> diskSidecarsByBlockNumber) {
        final var diskSidecars = diskSidecarsByBlockNumber.getOrDefault(blockNumber, List.of());
        if (wrbSidecars.size() != diskSidecars.size()) {
            Assertions.fail(String.format(
                    "Block %d: WRB has %d sidecar(s) but disk has %d",
                    blockNumber, wrbSidecars.size(), diskSidecars.size()));
            return;
        }
        for (int i = 0; i < wrbSidecars.size(); i++) {
            final var wrbBytes = SidecarFile.PROTOBUF.toBytes(wrbSidecars.get(i));
            final var diskBytes = diskSidecars.get(i);
            if (!wrbBytes.equals(diskBytes)) {
                Assertions.fail(String.format(
                        "Block %d sidecar[%d]: WRB bytes do not match disk (%d vs %d bytes)",
                        blockNumber, i, wrbBytes.length(), diskBytes.length()));
            }
        }
        if (!wrbSidecars.isEmpty()) {
            log.info("Block {}: {} sidecar(s) match disk", blockNumber, wrbSidecars.size());
        }
    }

    private static long blockNumberOf(@NonNull final Block block) {
        return block.items().getFirst().blockHeader().number();
    }
}
