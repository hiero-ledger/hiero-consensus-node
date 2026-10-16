// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter.maxParseSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.validation.ConfigViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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

        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = new WrappedRecordFileBlockHashesDiskWriter(
                app.configProvider(), java.nio.file.FileSystems.getDefault(), metrics)) {
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
            assertEquals(List.of(0L, (long) allBytes.length), publishedFileSizes(metrics));
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
    void truncatesValidExistingFileOverTheSizeLimit() throws IOException, ParseException {
        final long seededSize = seedEntries(configWithDiskWrites(tmpDir), 0, 1);

        // The seeded file parses fine, so only the size limit can drop it; re-appending block 1 also shows the
        // duplicate index was reset with the file
        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configWithDiskWrites(tmpDir, seededSize - 1), metrics)) {
            writer.appendAsync(inputFor(1)).join();
        }

        final var allBytes = Files.readAllBytes(tmpDir.resolve(FILE_NAME));
        assertEquals(List.of(1L), blockNumbersIn(allBytes), "Expected the file to be truncated before the append");
        assertEquals(List.of(0L, (long) allBytes.length), publishedFileSizes(metrics));
    }

    @Test
    void keepsExistingFileAtTheSizeLimit() throws IOException, ParseException {
        final long seededSize = seedEntries(configWithDiskWrites(tmpDir), 0, 1);

        try (final var writer = newWriter(configWithDiskWrites(tmpDir, seededSize), mock(BlockStreamMetrics.class))) {
            writer.appendAsync(inputFor(2)).join();
        }

        assertEquals(List.of(0L, 1L, 2L), blockNumbersIn(Files.readAllBytes(tmpDir.resolve(FILE_NAME))));
    }

    @Test
    void startsFromZeroWhenTheFileIsMissing() throws IOException {
        final var dir = tmpDir.resolve("missing");
        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configWithDiskWrites(dir), metrics)) {
            writer.appendAsync(inputFor(5)).join();
        }

        assertEquals(List.of(0L, Files.size(dir.resolve(FILE_NAME))), publishedFileSizes(metrics));
        verify(metrics).recordWrappedRecordHashesLowestBlock(-1);
        verify(metrics).recordWrappedRecordHashesHighestBlock(-1);
        verify(metrics).recordWrappedRecordHashesLowestBlock(5);
        verify(metrics).recordWrappedRecordHashesHighestBlock(5);
    }

    @Test
    void startsFromZeroWhenTheFileIsEmpty() throws IOException {
        Files.createFile(tmpDir.resolve(FILE_NAME));
        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configWithDiskWrites(tmpDir), metrics)) {
            writer.appendAsync(inputFor(0)).join();
        }

        assertEquals(List.of(0L, Files.size(tmpDir.resolve(FILE_NAME))), publishedFileSizes(metrics));
    }

    @Test
    void tracksFileSizeFromInitialScanAndAppends() throws IOException {
        final var configProvider = configWithDiskWrites(tmpDir);
        final var file = tmpDir.resolve(FILE_NAME);
        final long seededSize = seedEntries(configProvider, 0);

        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configProvider, metrics)) {
            writer.appendAsync(inputFor(1)).join();
            final long sizeAfterFirstAppend = Files.size(file);
            writer.appendAsync(inputFor(2)).join();

            assertEquals(
                    List.of(seededSize, sizeAfterFirstAppend, Files.size(file)),
                    publishedFileSizes(metrics),
                    "Expected the initial scan size, then the in-memory size after each append");
        }
    }

    @Test
    void skipsDuplicateBlocksWithoutChangingTheTrackedSize() throws IOException {
        final var configProvider = configWithDiskWrites(tmpDir);
        final long seededSize = seedEntries(configProvider, 0);

        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configProvider, metrics)) {
            writer.appendAsync(inputFor(0)).join();
        }

        assertEquals(seededSize, Files.size(tmpDir.resolve(FILE_NAME)));
        assertEquals(List.of(seededSize), publishedFileSizes(metrics));
    }

    @Test
    void doesNotCountFailedAppends() throws IOException {
        // A regular file where the directory should be makes every append fail
        final var notADirectory = Files.createFile(tmpDir.resolve("not-a-directory"));
        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configWithDiskWrites(notADirectory), metrics)) {
            writer.appendAsync(inputFor(0)).join();
        }

        assertEquals(List.of(0L), publishedFileSizes(metrics));
    }

    @Test
    void doesNothingWhenWritingIsDisabled() {
        final var configProvider = appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", tmpDir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", false)
                .build()
                .configProvider();
        final var metrics = mock(BlockStreamMetrics.class);
        try (final var writer = newWriter(configProvider, metrics)) {
            writer.appendAsync(inputFor(0)).join();
        }

        assertFalse(Files.exists(tmpDir.resolve(FILE_NAME)));
        verifyNoInteractions(metrics);
    }

    @Test
    void scansTheExistingFileOnTheCallerThread() throws IOException {
        final var configProvider = configWithDiskWrites(tmpDir);
        seedEntries(configProvider, 0);
        final var metrics = mock(BlockStreamMetrics.class);
        final List<Thread> publishingThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    publishingThreads.add(Thread.currentThread());
                    return null;
                })
                .when(metrics)
                .recordWrappedRecordHashesFileSizeBytes(anyLong());

        try (final var writer = newWriter(configProvider, metrics)) {
            writer.appendAsync(inputFor(1)).join();
        }

        // The first-use scan runs on the caller's (transaction handling) thread, the append on the writer thread
        assertEquals(2, publishingThreads.size());
        assertSame(Thread.currentThread(), publishingThreads.get(0));
        assertNotSame(Thread.currentThread(), publishingThreads.get(1));
    }

    @Test
    void defaultSizeLimitIsTwoBillionBytes() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(BlockRecordStreamConfig.class);
        assertEquals(2_000_000_000L, config.wrappedRecordHashesMaxFileSizeBytes());
    }

    @Test
    void sizeLimitCannotExceedWhatTheScanCanRead() {
        // Integer.MAX_VALUE - 8 is the largest array every JVM can allocate (the JDK's soft maximum array length)
        assertEquals(Integer.MAX_VALUE - 8, configWithSizeLimit(Integer.MAX_VALUE - 8));
        assertThrows(ConfigViolationException.class, () -> configWithSizeLimit(Integer.MAX_VALUE - 7L));
        assertThrows(ConfigViolationException.class, () -> configWithSizeLimit(0));
    }

    @Test
    void parseSizeLimitAddsHeadroomWithoutOverflowing() {
        assertEquals(0, maxParseSize(0));
        assertEquals(1_100, maxParseSize(1_000));
        // The largest length whose 10% headroom still fits in an int
        assertEquals(Integer.MAX_VALUE, maxParseSize(1_952_257_861));
        // Without the clamp these would overflow to a negative limit
        assertEquals(Integer.MAX_VALUE, maxParseSize(1_952_257_862));
        assertEquals(Integer.MAX_VALUE, maxParseSize(Integer.MAX_VALUE - 8));
    }

    private ConfigProvider configWithDiskWrites(final Path dir) {
        return appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", dir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .build()
                .configProvider();
    }

    private ConfigProvider configWithDiskWrites(final Path dir, final long maxFileSizeBytes) {
        return appBuilder()
                .withConfigValue("hedera.recordStream.wrappedRecordHashesDir", dir.toString())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .withConfigValue("hedera.recordStream.wrappedRecordHashesMaxFileSizeBytes", maxFileSizeBytes)
                .build()
                .configProvider();
    }

    private static long configWithSizeLimit(final long maxFileSizeBytes) {
        return HederaTestConfigBuilder.create()
                .withValue("hedera.recordStream.wrappedRecordHashesMaxFileSizeBytes", maxFileSizeBytes)
                .getOrCreateConfig()
                .getConfigData(BlockRecordStreamConfig.class)
                .wrappedRecordHashesMaxFileSizeBytes();
    }

    private static WrappedRecordFileBlockHashesDiskWriter newWriter(
            final ConfigProvider configProvider, final BlockStreamMetrics metrics) {
        return new WrappedRecordFileBlockHashesDiskWriter(
                configProvider, java.nio.file.FileSystems.getDefault(), metrics);
    }

    private long seedEntries(final ConfigProvider configProvider, final long... blockNumbers) throws IOException {
        try (final var seeder = newWriter(configProvider, mock(BlockStreamMetrics.class))) {
            for (final long blockNumber : blockNumbers) {
                seeder.appendAsync(inputFor(blockNumber)).join();
            }
        }
        return Files.size(tmpDir.resolve(FILE_NAME));
    }

    private static WrappedRecordFileBlockHashesComputationInput inputFor(final long blockNumber) {
        final var ts = new Timestamp(10 + 3 * blockNumber, 1);
        final var item = new RecordStreamItem(
                com.hedera.hapi.node.base.Transaction.DEFAULT,
                TransactionRecord.newBuilder().consensusTimestamp(ts).build());
        return new WrappedRecordFileBlockHashesComputationInput(
                blockNumber,
                ts,
                SemanticVersion.DEFAULT,
                Bytes.wrap(new byte[48]),
                Bytes.wrap(new byte[48]),
                List.of(item),
                List.of(),
                1024 * 1024);
    }

    private static List<Long> blockNumbersIn(final byte[] allBytes) throws ParseException {
        return WrappedRecordFileBlockHashesLog.PROTOBUF
                .parse(Bytes.wrap(allBytes).toReadableSequentialData(), false, false, 64, allBytes.length)
                .entries()
                .stream()
                .map(WrappedRecordFileBlockHashes::blockNumber)
                .toList();
    }

    private static List<Long> publishedFileSizes(final BlockStreamMetrics metrics) {
        final var captor = ArgumentCaptor.forClass(Long.class);
        verify(metrics, atLeastOnce()).recordWrappedRecordHashesFileSizeBytes(captor.capture());
        return captor.getAllValues();
    }
}
