// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.awaitility.Awaitility.await;

import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbPaths;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.base.file.FileUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@Fork(1)
@Threads(1)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(MILLISECONDS)
@State(Scope.Benchmark)
public class MerkleDbSnapshotBenchmark extends VirtualMapBaseBench {

    private static final int LONG_LIST_FILE_HEADER_SIZE = Integer.BYTES + Long.BYTES;
    private static final int LONG_LIST_FILE_FORMAT_VERSION = 3;
    private static final String TABLE_NAME = "state";
    private static final String BUCKET_INDEX_FILE_NAME = TABLE_NAME + "_objectkeytopath_bucket_index.ll";

    @Param({"false", "true"})
    public boolean useDiskIndices;

    @Param({"1", "2", "3", "6", "8", "16"})
    public int threadsPerLongList;

    @Param({"FORCED", "UNFORCED", "FORCED_OVERLAP", "UNFORCED_OVERLAP"})
    public SnapshotMode snapshotMode;

    private MerkleDbDataSource source;
    private Path snapshotDirectory;
    private int longsPerChunk;

    @Override
    String benchmarkName() {
        return "MerkleDbSnapshotBenchmark";
    }

    @Override
    protected void configureBenchmarkConfiguration(final ConfigurationBuilder configurationBuilder) {
        super.configureBenchmarkConfiguration(configurationBuilder);
        configurationBuilder.withSource(new SimpleConfigSource()
                .withValue("benchmark.saveDataDirectory", true)
                .withValue("benchmark.csvWriteFrequency", 0)
                .withValue("merkleDb.useDiskIndices", useDiskIndices)
                .withValue("merkleDb.longListSnapshotThreadsPerList", threadsPerLongList)
                .withValue("merkleDb.longListSnapshotForceToDisk", snapshotMode.forceToDisk)
                .withValue("merkleDb.snapshotHashCacheFlushOverlap", snapshotMode.overlapHashCacheFlush)
                .withOrdinal(Integer.MAX_VALUE));
    }

    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();

        final MerkleDbConfig merkleDbConfig = getConfig(MerkleDbConfig.class);
        dataSourceBuilder =
                new MerkleDbDataSourceBuilder(configuration, fileSystemManager, merkleDbConfig.initialCapacity());
        longsPerChunk = merkleDbConfig.longListChunkSize();

        try {
            final Path fixtureDirectory = fixtureDirectory(merkleDbConfig);
            createFixtureIfNeeded(fixtureDirectory);
            source = (MerkleDbDataSource) dataSourceBuilder.build(TABLE_NAME, fixtureDirectory, false, false);
            validateSource();
            populateHashChunkCache(merkleDbConfig);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void onInvocationSetup() {
        super.onInvocationSetup();
        snapshotDirectory = fileSystemManager.resolveNewTemp("snapshot-output");
    }

    @Benchmark
    public Path snapshot() {
        return dataSourceBuilder.snapshot(snapshotDirectory, source);
    }

    @Override
    protected void onInvocationTearDown() throws Exception {
        try {
            final long start = System.currentTimeMillis();
            forceSnapshotFiles(snapshotDirectory);
            logger.info("Forced snapshot files after return in {} ms", System.currentTimeMillis() - start);
            validateSnapshot();
        } finally {
            Utils.deleteRecursively(snapshotDirectory);
            snapshotDirectory = null;
            super.onInvocationTearDown();
        }
    }

    @Override
    protected void onTrialTearDown() throws Exception {
        try {
            if (source != null) {
                source.close();
                source = null;
            }
            await().atMost(Duration.ofSeconds(30)).until(() -> MerkleDbDataSource.getCountOfOpenDatabases() == 0);
        } finally {
            super.onTrialTearDown();
        }
    }

    private Path fixtureDirectory(final MerkleDbConfig merkleDbConfig) {
        final long stateSize = Math.multiplyExact((long) numFiles, numRecords);
        return getBenchDir()
                .resolve("fixture-%d-k%d-r%d-cap%d-h%d"
                        .formatted(
                                stateSize,
                                keySize,
                                recordSize,
                                merkleDbConfig.initialCapacity(),
                                merkleDbConfig.hashChunkHeight()));
    }

    private void createFixtureIfNeeded(final Path fixtureDirectory) throws IOException {
        if (Files.isDirectory(fixtureDirectory)) {
            return;
        }

        final Path temporaryFixtureDirectory = fixtureDirectory.resolveSibling(fixtureDirectory.getFileName() + ".tmp");
        Utils.deleteRecursively(temporaryFixtureDirectory);

        final AtomicReference<VirtualMap> mapReference = new AtomicReference<>(createEmptyMap());
        try {
            final long stateSize = Math.multiplyExact((long) numFiles, numRecords);
            final long start = System.currentTimeMillis();
            new StateBuilder(BenchmarkKeyUtils::longToKey, BenchmarkValue::new)
                    .populateState(
                            0,
                            stateSize,
                            i -> {
                                if (i > 0 && i % numRecords == 0) {
                                    final VirtualMap map = mapReference.get();
                                    mapReference.set(copyMap(map));
                                }
                            },
                            StateBuilder.buildVMPopulator(mapReference));
            logger.info("Pre-created {} records in {} ms", stateSize, System.currentTimeMillis() - start);

            mapReference.set(flushMap(mapReference.get()));
            final MerkleDbDataSource fixtureSource =
                    (MerkleDbDataSource) mapReference.get().getDataSource();
            FileUtils.executeAndRename(fixtureDirectory, temporaryFixtureDirectory, directory -> {
                dataSourceBuilder.snapshot(directory, fixtureSource);
                validateSnapshot(fixtureSource, directory);
                forceSnapshotFiles(directory);
            });
        } finally {
            mapReference.get().release();
        }
    }

    private void validateSource() {
        final long stateSize = Math.multiplyExact((long) numFiles, numRecords);
        final long expectedFirstLeafPath = stateSize - 1;
        final long expectedLastLeafPath = stateSize * 2 - 2;
        if (source.getFirstLeafPath() != expectedFirstLeafPath || source.getLastLeafPath() != expectedLastLeafPath) {
            throw new IllegalStateException("Fixture leaf range is "
                    + source.getFirstLeafPath()
                    + "-"
                    + source.getLastLeafPath()
                    + ", expected "
                    + expectedFirstLeafPath
                    + "-"
                    + expectedLastLeafPath);
        }
    }

    private void populateHashChunkCache(final MerkleDbConfig merkleDbConfig) throws IOException {
        final long lastChunkId =
                VirtualHashChunk.lastChunkIdForPaths(source.getLastLeafPath(), source.getHashChunkHeight());
        final long cachedChunkCount = Math.min((long) merkleDbConfig.hashChunkCacheThreshold(), lastChunkId + 1);
        for (long chunkId = 0; chunkId < cachedChunkCount; chunkId++) {
            if (source.loadHashChunk(chunkId) == null) {
                throw new IOException("Missing hash chunk " + chunkId);
            }
        }
        logger.info("Loaded {} hash chunks into the snapshot source cache", cachedChunkCount);
    }

    private static void forceSnapshotFiles(final Path directory) throws IOException {
        final List<Path> snapshotFiles;
        try (final Stream<Path> files = Files.walk(directory)) {
            snapshotFiles = files.filter(Files::isRegularFile).toList();
        }
        for (final Path file : snapshotFiles) {
            try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
    }

    private void validateSnapshot() throws IOException {
        validateSnapshot(source, snapshotDirectory);
    }

    private void validateSnapshot(final MerkleDbDataSource expected, final Path directory) throws IOException {
        final MerkleDbPaths snapshotPaths =
                new MerkleDbPaths(directory.resolve("data").resolve(TABLE_NAME));
        validateLongListSnapshot(
                expected.getIdToDiskLocationHashChunks(), snapshotPaths.idToDiskLocationHashChunksFile);
        validateLongListSnapshot(
                expected.getPathToDiskLocationLeafNodes(), snapshotPaths.pathToDiskLocationLeafNodesFile);
        validateLongListSnapshot(
                expected.getKeyToPath().getBucketIndexToBucketLocation(),
                snapshotPaths.keyToPathDirectory.resolve(BUCKET_INDEX_FILE_NAME));

        if (!Files.isRegularFile(snapshotPaths.metadataFile)) {
            throw new IOException("Snapshot metadata is missing: " + snapshotPaths.metadataFile);
        }
        validateStoreDirectory(snapshotPaths.hashChunkDirectory);
        validateStoreDirectory(snapshotPaths.keyToPathDirectory);
        validateStoreDirectory(snapshotPaths.pathToKeyValueDirectory);
    }

    private void validateLongListSnapshot(final LongList expected, final Path snapshotFile) throws IOException {
        final long minValidIndex = expected.getMinValidIndex();
        final long size = expected.size();
        final long expectedFileSize = LONG_LIST_FILE_HEADER_SIZE + Math.multiplyExact(size - minValidIndex, Long.BYTES);
        final long actualFileSize = Files.size(snapshotFile);
        if (actualFileSize != expectedFileSize) {
            throw new IOException(
                    "Unexpected snapshot size for " + snapshotFile + ": " + actualFileSize + " != " + expectedFileSize);
        }

        try (final FileChannel channel = FileChannel.open(snapshotFile, StandardOpenOption.READ)) {
            final ByteBuffer header = ByteBuffer.allocate(LONG_LIST_FILE_HEADER_SIZE);
            readFully(channel, header, 0);
            header.flip();
            final int formatVersion = header.getInt();
            final long fileMinValidIndex = header.getLong();
            if (formatVersion != LONG_LIST_FILE_FORMAT_VERSION || fileMinValidIndex != minValidIndex) {
                throw new IOException("Unexpected LongList header in " + snapshotFile);
            }

            validateLongValue(channel, expected, minValidIndex);
            final long firstChunkBoundary = (minValidIndex / longsPerChunk + 1) * longsPerChunk;
            for (long index = firstChunkBoundary; index < size; index += longsPerChunk) {
                validateLongValue(channel, expected, index);
            }
            if (size - 1 != minValidIndex) {
                validateLongValue(channel, expected, size - 1);
            }
        }
    }

    private static void validateStoreDirectory(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Snapshot store directory is missing: " + directory);
        }
        try (final Stream<Path> files = Files.walk(directory)) {
            if (files.noneMatch(Files::isRegularFile)) {
                throw new IOException("Snapshot store directory is empty: " + directory);
            }
        }
    }

    private static void validateLongValue(final FileChannel channel, final LongList expected, final long index)
            throws IOException {
        final long position =
                LONG_LIST_FILE_HEADER_SIZE + Math.multiplyExact(index - expected.getMinValidIndex(), Long.BYTES);
        final ByteBuffer valueBuffer = ByteBuffer.allocate(Long.BYTES).order(LITTLE_ENDIAN);
        readFully(channel, valueBuffer, position);
        valueBuffer.flip();
        final long actualValue = valueBuffer.getLong();
        final long expectedValue = expected.get(index, 0);
        if (actualValue != expectedValue) {
            throw new IOException(
                    "Unexpected LongList value at index " + index + ": " + actualValue + " != " + expectedValue);
        }
    }

    private static void readFully(final FileChannel channel, final ByteBuffer buffer, final long position)
            throws IOException {
        while (buffer.hasRemaining()) {
            final int bytesRead = channel.read(buffer, position + buffer.position());
            if (bytesRead < 0) {
                throw new EOFException("Unexpected end of snapshot file");
            }
        }
    }

    public enum SnapshotMode {
        FORCED(true, false),
        UNFORCED(false, false),
        FORCED_OVERLAP(true, true),
        UNFORCED_OVERLAP(false, true);

        private final boolean forceToDisk;
        private final boolean overlapHashCacheFlush;

        SnapshotMode(final boolean forceToDisk, final boolean overlapHashCacheFlush) {
            this.forceToDisk = forceToDisk;
            this.overlapHashCacheFlush = overlapHashCacheFlush;
        }
    }
}
