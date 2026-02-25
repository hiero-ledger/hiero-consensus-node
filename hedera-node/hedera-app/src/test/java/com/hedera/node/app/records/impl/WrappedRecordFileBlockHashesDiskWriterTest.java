// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrappedRecordFileBlockHashesDiskWriterTest extends AppTestBase {
    private static final String FILE_NAME = "wrapped-record-hashes.pb";

    @TempDir
    Path tmpDir;

    @Test
    void truncatesCorruptExistingFileOnInit() throws IOException, ParseException {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .build();

        // Seed a corrupt file that cannot be parsed
        final var file = tmpDir.resolve(FILE_NAME);
        Files.createDirectories(tmpDir);
        Files.write(file, new byte[] {0x01, 0x02, 0x03});

        try (final var writer = new WrappedRecordFileBlockHashesDiskWriter(
                app.configProvider(), java.nio.file.FileSystems.getDefault(), mock(BlockStreamMetrics.class))) {
            final var ts0 = new Timestamp(10, 1);
            final var item0 = new RecordStreamItem(
                    com.hedera.hapi.node.base.Transaction.DEFAULT,
                    TransactionRecord.newBuilder().consensusTimestamp(ts0).build());

            final var in0 = new WrappedRecordFileBlockHashesComputationInput(
                    0,
                    ts0,
                    SemanticVersion.DEFAULT,
                    Bytes.wrap(new byte[48]),
                    Bytes.wrap(new byte[48]),
                    List.of(item0),
                    List.of(),
                    1024 * 1024);

            // Should succeed even though the existing file was corrupt
            writer.appendAsync(in0).join();

            final var allBytes = Files.readAllBytes(file);
            final WrappedRecordFileBlockHashesLog log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                    Bytes.wrap(allBytes).toReadableSequentialData(), false, false, 64, allBytes.length);
            assertEquals(1, log.entries().size(), "Expected corrupt file to be truncated and rewritten");
        }
    }

    @Test
    void appendsRepeatedFieldOccurrencesThatParseBack() throws IOException, ParseException {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .build();

        try (final var writer = new WrappedRecordFileBlockHashesDiskWriter(
                app.configProvider(), java.nio.file.FileSystems.getDefault(), mock(BlockStreamMetrics.class))) {
            final var ts0 = new Timestamp(10, 1);
            final var ts1 = new Timestamp(13, 1);
            final var item0 = new RecordStreamItem(
                    com.hedera.hapi.node.base.Transaction.DEFAULT,
                    TransactionRecord.newBuilder().consensusTimestamp(ts0).build());
            final var item1 = new RecordStreamItem(
                    com.hedera.hapi.node.base.Transaction.DEFAULT,
                    TransactionRecord.newBuilder().consensusTimestamp(ts1).build());

            final var in0 = new WrappedRecordFileBlockHashesComputationInput(
                    0,
                    ts0,
                    SemanticVersion.DEFAULT,
                    Bytes.wrap(new byte[48]),
                    Bytes.wrap(new byte[48]),
                    List.of(item0),
                    List.of(),
                    1024 * 1024);
            final var in1 = new WrappedRecordFileBlockHashesComputationInput(
                    1,
                    ts1,
                    SemanticVersion.DEFAULT,
                    Bytes.wrap(new byte[48]),
                    Bytes.wrap(new byte[48]),
                    List.of(item1),
                    List.of(),
                    1024 * 1024);

            final var e0 = WrappedRecordFileBlockHashesCalculator.compute(in0);
            final var e1 = WrappedRecordFileBlockHashesCalculator.compute(in1);

            writer.appendAsync(in0).join();
            // A reconnected/restarted node may attempt to append a block that was already appended earlier;
            // ensure the writer treats this as a no-op.
            writer.appendAsync(in0).join();
            writer.appendAsync(in1).join();

            final var file = tmpDir.resolve(FILE_NAME);
            assertTrue(Files.exists(file), "Expected file to be created");

            final var allBytes = Files.readAllBytes(file);
            final WrappedRecordFileBlockHashesLog log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                    Bytes.wrap(allBytes).toReadableSequentialData(), false, false, 64, allBytes.length);

            assertEquals(List.of(e0, e1), log.entries(), "Expected entries to parse back from the on-disk file");
        }
    }

    @Test
    void appendPrecomputedWritesEntryToDisk() throws IOException, ParseException {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .build();

        try (final var writer = new WrappedRecordFileBlockHashesDiskWriter(
                app.configProvider(), java.nio.file.FileSystems.getDefault(), mock(BlockStreamMetrics.class))) {

            // Pre-compute an entry manually
            final var ts0 = new Timestamp(10, 1);
            final var item0 = new RecordStreamItem(
                    com.hedera.hapi.node.base.Transaction.DEFAULT,
                    TransactionRecord.newBuilder().consensusTimestamp(ts0).build());
            final var in0 = new WrappedRecordFileBlockHashesComputationInput(
                    0,
                    ts0,
                    SemanticVersion.DEFAULT,
                    Bytes.wrap(new byte[48]),
                    Bytes.wrap(new byte[48]),
                    List.of(item0),
                    List.of(),
                    1024 * 1024);
            final var entry = WrappedRecordFileBlockHashesCalculator.compute(in0);

            // Use appendPrecomputed instead of appendAsync
            writer.appendPrecomputed(entry).join();

            final var file = tmpDir.resolve(FILE_NAME);
            assertTrue(Files.exists(file), "Expected file to be created by appendPrecomputed");

            final var allBytes = Files.readAllBytes(file);
            final WrappedRecordFileBlockHashesLog log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                    Bytes.wrap(allBytes).toReadableSequentialData(), false, false, 64, allBytes.length);
            assertEquals(1, log.entries().size());
            assertEquals(entry, log.entries().getFirst());
        }
    }

    @Test
    void appendPrecomputedSkipsWhenFeatureFlagDisabled() throws IOException {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", false)
                .build();

        try (final var writer = new WrappedRecordFileBlockHashesDiskWriter(
                app.configProvider(), java.nio.file.FileSystems.getDefault(), mock(BlockStreamMetrics.class))) {

            final var entry = WrappedRecordFileBlockHashes.newBuilder()
                    .blockNumber(0)
                    .consensusTimestampHash(Bytes.wrap(new byte[48]))
                    .outputItemsTreeRootHash(Bytes.wrap(new byte[48]))
                    .build();

            writer.appendPrecomputed(entry).join();

            final var file = tmpDir.resolve(FILE_NAME);
            assertTrue(!Files.exists(file), "File should not be created when feature flag is disabled");
        }
    }

    @Test
    void appendPrecomputedDeduplicatesExistingBlockNumber() throws IOException, ParseException {
        final var app = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .build();

        try (final var writer = new WrappedRecordFileBlockHashesDiskWriter(
                app.configProvider(), java.nio.file.FileSystems.getDefault(), mock(BlockStreamMetrics.class))) {

            final var ts0 = new Timestamp(10, 1);
            final var item0 = new RecordStreamItem(
                    com.hedera.hapi.node.base.Transaction.DEFAULT,
                    TransactionRecord.newBuilder().consensusTimestamp(ts0).build());
            final var in0 = new WrappedRecordFileBlockHashesComputationInput(
                    0,
                    ts0,
                    SemanticVersion.DEFAULT,
                    Bytes.wrap(new byte[48]),
                    Bytes.wrap(new byte[48]),
                    List.of(item0),
                    List.of(),
                    1024 * 1024);
            final var entry = WrappedRecordFileBlockHashesCalculator.compute(in0);

            // Append the same entry twice via appendPrecomputed
            writer.appendPrecomputed(entry).join();
            writer.appendPrecomputed(entry).join();

            final var file = tmpDir.resolve(FILE_NAME);
            final var allBytes = Files.readAllBytes(file);
            final WrappedRecordFileBlockHashesLog log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                    Bytes.wrap(allBytes).toReadableSequentialData(), false, false, 64, allBytes.length);
            assertEquals(1, log.entries().size(), "Duplicate block should not be appended");
        }
    }
}
