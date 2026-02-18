// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.SidecarMetadata;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.records.impl.WrappedRecordSidecarUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code BlockRecordManagerImpl.buildSidecarBundles(...)} matches the actual v6 sidecar writer semantics:
 * rollover rule, type tracking, and compressed-bytes SHA-384 hashing.
 */
class BuildSidecarBundlesV6CompatibilityTest {
    @TempDir
    Path tempDir;

    @Test
    void buildSidecarBundlesMatchesSidecarWriterV6() throws Exception {
        final var ts = new Timestamp(1_770_063_269L, 528_859_895);

        final var bytecodeA = TransactionSidecarRecord.newBuilder()
                .consensusTimestamp(ts)
                .bytecode(ContractBytecode.newBuilder()
                        .contractId(ContractID.DEFAULT)
                        .runtimeBytecode(Bytes.wrap(new byte[500]))
                        .build())
                .build();
        final var actions = TransactionSidecarRecord.newBuilder()
                .consensusTimestamp(ts)
                .actions(ContractActions.newBuilder().contractActions(List.of()).build())
                .build();
        final var stateChanges = TransactionSidecarRecord.newBuilder()
                .consensusTimestamp(ts)
                .stateChanges(ContractStateChanges.newBuilder()
                        .contractStateChanges(List.of())
                        .build())
                .build();
        final var bytecodeB = TransactionSidecarRecord.newBuilder()
                .consensusTimestamp(ts)
                .bytecode(ContractBytecode.newBuilder()
                        .contractId(ContractID.DEFAULT)
                        .runtimeBytecode(Bytes.wrap(new byte[500]))
                        .build())
                .build();

        final var records = List.of(bytecodeA, actions, stateChanges, bytecodeB);
        final var recordLens = records.stream()
                .mapToInt(
                        r -> (int) TransactionSidecarRecord.PROTOBUF.toBytes(r).length())
                .toArray();

        // Choose a max size so the first three fit, but adding the fourth triggers rollover.
        final int maxSidecarBytes = recordLens[0] + recordLens[1] + recordLens[2];
        // Preconditions: each record must be smaller than max, otherwise SidecarWriterV6 can't write it to an empty
        // file.
        for (final int len : recordLens) {
            assertTrue(len <= maxSidecarBytes);
        }

        final var expected = expectedBundlesFromActualWriter(records, maxSidecarBytes);
        final var actual = bundlesFromBuildSidecarBundles(records, maxSidecarBytes);

        assertEquals(expected.sidecarFiles().size(), actual.sidecarFiles().size(), "Sidecar file count mismatch");
        assertEquals(
                expected.sidecarMetadata().size(), actual.sidecarMetadata().size(), "Sidecar metadata count mismatch");

        for (int i = 0; i < expected.sidecarFiles().size(); i++) {
            final var expectedFile = expected.sidecarFiles().get(i);
            final var actualFile = actual.sidecarFiles().get(i);
            assertEquals(
                    expectedFile.sidecarRecords(),
                    actualFile.sidecarRecords(),
                    "Sidecar file record grouping mismatch");

            final var expectedMeta = expected.sidecarMetadata().get(i);
            final var actualMeta = actual.sidecarMetadata().get(i);
            assertEquals(expectedMeta.id(), actualMeta.id(), "Sidecar metadata id mismatch");
            assertEquals(expectedMeta.types(), actualMeta.types(), "Sidecar metadata types mismatch");
            assertEquals(expectedMeta.hash().algorithm(), actualMeta.hash().algorithm());
            assertEquals(expectedMeta.hash().length(), actualMeta.hash().length());
            assertArrayEquals(
                    expectedMeta.hash().hash().toByteArray(),
                    actualMeta.hash().hash().toByteArray(),
                    "Sidecar metadata hash mismatch");
        }
    }

    private record SidecarBundles(List<SidecarFile> sidecarFiles, List<SidecarMetadata> sidecarMetadata) {}

    private SidecarBundles bundlesFromBuildSidecarBundles(
            final List<TransactionSidecarRecord> records, final int maxSidecarBytes) throws Exception {
        final var bundles = WrappedRecordSidecarUtils.buildSidecarBundles(records, maxSidecarBytes);
        return new SidecarBundles(bundles.sidecarFiles(), bundles.sidecarMetadata());
    }

    private SidecarBundles expectedBundlesFromActualWriter(
            final List<TransactionSidecarRecord> records, final int maxSidecarBytes) throws Exception {
        final List<SidecarFile> files = new ArrayList<>();
        final List<SidecarMetadata> metadata = new ArrayList<>();

        int id = 1;
        SidecarWriterV6 writer = new SidecarWriterV6(sidecarPath(id), maxSidecarBytes, id);
        List<TransactionSidecarRecord> currentFileRecords = new ArrayList<>();

        for (final var record : records) {
            final var recordBytes = TransactionSidecarRecord.PROTOBUF.toBytes(record);
            final var kind = record.sidecarRecords().kind();

            if (!writer.writeTransactionSidecarRecord(kind, recordBytes)) {
                writer.close();
                files.add(new SidecarFile(new ArrayList<>(currentFileRecords)));
                metadata.add(new SidecarMetadata(
                        new HashObject(
                                HashAlgorithm.SHA_384, (int) writer.fileHash().length(), writer.fileHash()),
                        id,
                        writer.types()));

                // rollover
                id++;
                writer = new SidecarWriterV6(sidecarPath(id), maxSidecarBytes, id);
                currentFileRecords = new ArrayList<>();

                final boolean wroteAfterRollover = writer.writeTransactionSidecarRecord(kind, recordBytes);
                assertTrue(wroteAfterRollover, "Record should fit in an empty sidecar file");
            }

            currentFileRecords.add(record);
        }

        writer.close();
        files.add(new SidecarFile(new ArrayList<>(currentFileRecords)));
        metadata.add(new SidecarMetadata(
                new HashObject(HashAlgorithm.SHA_384, (int) writer.fileHash().length(), writer.fileHash()),
                id,
                writer.types()));

        return new SidecarBundles(files, metadata);
    }

    private Path sidecarPath(final int id) {
        return tempDir.resolve("sidecar_" + id + ".rcd.gz");
    }
}
