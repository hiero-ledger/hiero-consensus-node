// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.awaitility.Awaitility.await;

import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListDisk;
import com.swirlds.merkledb.collections.LongListDiskSegment;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.collections.LongListImplementation;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.collections.LongListSegment;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.internal.MerkleDbDataSource;
import com.swirlds.merkledb.internal.MerkleDbPaths;
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
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.file.FileUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Warmup;

/// Snapshot campaign version of the benchmark. The campaign runner copies it over the benchmark of
/// every build it compares, together with the LongList implementation selector patch.
///
/// Measures complete snapshots of a state containing `numFiles * numRecords` leaves.
/// Fixture generation and loading, file forcing, and validation are outside the measured operation.
/// The selected implementation is used for all three snapshot indices.
@Fork(1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(MILLISECONDS)
public class MerkleDbSnapshotBenchmark extends VirtualMapBaseBench {

    private static final String TABLE_NAME = "state";
    private static final String BUCKET_INDEX_FILE_NAME = TABLE_NAME + "_objectkeytopath_bucket_index.ll";
    private static final int LONG_LIST_FILE_HEADER_SIZE = Integer.BYTES + Long.BYTES;
    private static final int LONG_LIST_FILE_FORMAT_VERSION = 3;

    @Param({"SEGMENT", "DISK", "HEAP", "OFF_HEAP", "DISK_SEGMENT"})
    public LongListImplementation longListImplementation;

    /// Threads per LongList file in the range build, threads per snapshot in the shared-pool build,
    /// ignored by the main build.
    @Param({"1"})
    public int longListWriteThreads;

    /// Generates and saves the fixture, then returns without measuring a snapshot.
    @Param({"false"})
    public boolean prepareFixtureOnly;

    private MerkleDbDataSource source;
    private Path fixtureDirectory;
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
                .withValue("benchmark.csvWriteFrequency", 0)
                .withValue("merkleDb.useDiskIndices", longListImplementation.isDiskBased())
                .withValue("merkleDb.longListWriteThreads", longListWriteThreads)
                .withOrdinal(Integer.MAX_VALUE));
    }

    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();
        // The selector patch reads this before the data source creates or loads its indices.
        System.setProperty(LongListImplementation.SYSTEM_PROPERTY, longListImplementation.name());

        final MerkleDbConfig merkleDbConfig = getConfig(MerkleDbConfig.class);
        dataSourceBuilder =
                new MerkleDbDataSourceBuilder(configuration, fileSystemManager, merkleDbConfig.initialCapacity());
        longsPerChunk = merkleDbConfig.longListChunkSize();

        try {
            fixtureDirectory = fixtureDirectory(merkleDbConfig);
            // Campaigns reuse a saved fixture; a missing one must fail instead of starting a long generation.
            if (Boolean.getBoolean("benchmark.requireExistingFixture") && !Files.isDirectory(fixtureDirectory)) {
                throw new IOException("Missing saved fixture: " + fixtureDirectory);
            }
            createFixture(fixtureDirectory);
            if (prepareFixtureOnly) {
                logger.info("Fixture preparation complete: {} (no measured snapshot)", fixtureDirectory);
                return;
            }
            source = (MerkleDbDataSource) dataSourceBuilder.build(TABLE_NAME, fixtureDirectory, false, false);
            validateSource();
            populateHashChunkCache(merkleDbConfig);
            // Loading disk-based indices writes new files; finish those writes before the warmup.
            final Path tempPath = fileSystemManager.getTempPath();
            if (Files.isDirectory(tempPath)) {
                forceFiles(tempPath);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void onInvocationSetup() {
        super.onInvocationSetup();
        if (!prepareFixtureOnly) {
            snapshotDirectory = getBenchDir().resolve("snapshot-output");
            // Remove output left by an interrupted run.
            Utils.deleteRecursively(snapshotDirectory);
        }
    }

    /// Snapshots the prepared data source.
    ///
    /// @return snapshot directory, or the fixture directory when only preparing the fixture
    @Benchmark
    public Path snapshot() {
        if (prepareFixtureOnly) {
            return fixtureDirectory;
        }
        return dataSourceBuilder.snapshot(snapshotDirectory, source);
    }

    @Override
    protected void onInvocationTearDown() throws Exception {
        try {
            if (!prepareFixtureOnly) {
                // Drain outside the timed method so pending writes do not accumulate between invocations.
                final long start = System.currentTimeMillis();
                forceFiles(snapshotDirectory);
                logger.info("Forced snapshot files after return in {} ms", System.currentTimeMillis() - start);
                validateSnapshot(source, snapshotDirectory);
            }
        } finally {
            if (snapshotDirectory != null) {
                Utils.deleteRecursively(snapshotDirectory);
                snapshotDirectory = null;
            }
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
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> MerkleDbDataSourceBuilder.getCountOfOpenDatabases() == 0);
        } finally {
            super.onTrialTearDown();
        }
    }

    /// Returns a fixture directory for these record and database settings.
    ///
    /// @param merkleDbConfig database settings used to build the fixture
    /// @return fixture path inside the benchmark directory
    private Path fixtureDirectory(final MerkleDbConfig merkleDbConfig) {
        final long stateSize = Math.multiplyExact((long) numFiles, numRecords);
        // The framework keeps this fixture across trials when benchmark.saveDataDirectory is enabled.
        return getBenchDir()
                .resolve("fixture-%d-k%d-r%d-cap%d-h%d-d%s"
                        .formatted(
                                stateSize,
                                keySize,
                                recordSize,
                                merkleDbConfig.initialCapacity(),
                                merkleDbConfig.hashChunkHeight(),
                                Cryptography.DEFAULT_DIGEST_TYPE));
    }

    /// Reuses a saved fixture, or creates one and saves it after all writes finish.
    ///
    /// @param fixtureDirectory reusable fixture location
    /// @throws IOException if fixture files cannot be written
    private void createFixture(final Path fixtureDirectory) throws IOException {
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
            // Finish compactions before comparing saved index values with the live source.
            fixtureSource.awaitForCurrentCompactionsToComplete(0);
            fixtureSource.stopAndDisableBackgroundCompaction(false);

            FileUtils.executeAndRename(fixtureDirectory, temporaryFixtureDirectory, directory -> {
                dataSourceBuilder.snapshot(directory, fixtureSource);
                validateSnapshot(fixtureSource, directory);
                forceFiles(directory);
            });
        } finally {
            mapReference.get().release();
        }
    }

    /// Checks that the fixture matches the parameters and that every index uses the selected implementation.
    private void validateSource() {
        final long stateSize = Math.multiplyExact((long) numFiles, numRecords);
        if (source.getFirstLeafPath() != stateSize - 1 || source.getLastLeafPath() != stateSize * 2 - 2) {
            throw new IllegalStateException("Fixture leaf range is " + source.getFirstLeafPath() + "-"
                    + source.getLastLeafPath() + ", expected " + (stateSize - 1) + "-" + (stateSize * 2 - 2));
        }
        if (source.getLoadedHashDigestType() != Cryptography.DEFAULT_DIGEST_TYPE) {
            throw new IllegalStateException("Fixture hash digest does not match the current default");
        }
        final Class<? extends LongList> expectedClass =
                switch (longListImplementation) {
                    case HEAP -> LongListHeap.class;
                    case OFF_HEAP -> LongListOffHeap.class;
                    case SEGMENT -> LongListSegment.class;
                    case DISK -> LongListDisk.class;
                    case DISK_SEGMENT -> LongListDiskSegment.class;
                };
        final List<LongList> indices = List.of(
                source.getIdToDiskLocationHashChunks(),
                source.getPathToDiskLocationLeafNodes(),
                source.getKeyToPath().getBucketIndexToBucketLocation());
        for (final LongList index : indices) {
            if (index.getClass() != expectedClass) {
                throw new IllegalStateException("Index uses " + index.getClass().getSimpleName() + " instead of "
                        + expectedClass.getSimpleName() + "; is the selector patch applied?");
            }
        }
        logger.info("Snapshot source indices (hashes, leaves, buckets) use {}", expectedClass.getSimpleName());
    }

    /// Loads hash chunks into the cache so snapshots have cached hashes to write.
    ///
    /// @param merkleDbConfig database settings defining the cache limit
    /// @throws IOException if a hash chunk cannot be read
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

    /// Waits for file writes to reach storage so they do not carry over into the next measurement.
    ///
    /// @param directory directory containing the files to flush
    /// @throws IOException if a file cannot be opened or flushed
    private static void forceFiles(final Path directory) throws IOException {
        final List<Path> files;
        try (final Stream<Path> paths = Files.walk(directory)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        for (final Path file : files) {
            try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
    }

    /// Validates the three index files and the presence of the snapshot stores.
    ///
    /// @param expected data source the snapshot was taken from
    /// @param directory snapshot directory
    /// @throws IOException if a file is missing or differs from the source
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

    /// Checks an index file's size and header, its first and last values, and the first value of every chunk.
    ///
    /// @param expected live index
    /// @param snapshotFile index file written by the snapshot
    /// @throws IOException if the file differs from the live index
    private void validateLongListSnapshot(final LongList expected, final Path snapshotFile) throws IOException {
        final long minValidIndex = expected.getMinValidIndex();
        final long size = expected.size();
        final long expectedFileSize =
                LONG_LIST_FILE_HEADER_SIZE + (size > 0 ? Math.multiplyExact(size - minValidIndex, Long.BYTES) : 0);
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

            if (size == 0) {
                return;
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

    /// Requires a store directory to contain at least one regular file.
    ///
    /// @param directory store directory
    /// @throws IOException if the directory is missing or empty
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

    /// Compares one value of an index file with the live index.
    ///
    /// @param channel index file
    /// @param expected live index
    /// @param index index to compare
    /// @throws IOException if the values differ
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

    /// Fills a buffer from a file, failing on a truncated file.
    ///
    /// @param channel file to read
    /// @param buffer buffer to fill
    /// @param position file position of the first byte
    /// @throws IOException if the file ends first
    private static void readFully(final FileChannel channel, final ByteBuffer buffer, final long position)
            throws IOException {
        while (buffer.hasRemaining()) {
            final int bytesRead = channel.read(buffer, position + buffer.position());
            if (bytesRead < 0) {
                throw new EOFException("Unexpected end of snapshot file");
            }
        }
    }
}
