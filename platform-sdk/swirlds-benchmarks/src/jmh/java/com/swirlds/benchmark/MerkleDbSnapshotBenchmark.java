// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.awaitility.Awaitility.await;

import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.collections.LongListImplementation;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

    private static final String TABLE_NAME = "state";

    @Param({"SEGMENT", "DISK", "HEAP", "OFF_HEAP", "DISK_SEGMENT"})
    public LongListImplementation longListImplementation;

    @Param({"1", "2", "8", "16", "32"})
    public int threadsPerLongList;

    @Param({"FORCED", "UNFORCED", "FORCED_OVERLAP", "UNFORCED_OVERLAP"})
    public SnapshotMode snapshotMode;

    private VirtualDataSource source;
    private Path snapshotDirectory;

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
                .withValue("merkleDb.longListSnapshotThreadsPerList", threadsPerLongList)
                .withValue("merkleDb.longListSnapshotForceToDisk", snapshotMode.forceToDisk)
                .withValue("merkleDb.snapshotHashCacheFlushOverlap", snapshotMode.overlapHashCacheFlush)
                .withOrdinal(Integer.MAX_VALUE));
    }

    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();

        final MerkleDbConfig merkleDbConfig = getConfig(MerkleDbConfig.class);
        dataSourceBuilder = new MerkleDbDataSourceBuilder(
                configuration, fileSystemManager, merkleDbConfig.initialCapacity(), longListImplementation);

        try {
            final Path fixtureDirectory = fixtureDirectory(merkleDbConfig);
            createFixtureIfNeeded(fixtureDirectory);
            source = dataSourceBuilder.build(TABLE_NAME, fixtureDirectory, false, false);
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
            await().atMost(Duration.ofSeconds(30)).until(() -> MerkleDbDataSourceBuilder.getCountOfOpenDatabases() == 0);
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
            final VirtualDataSource fixtureSource = mapReference.get().getDataSource();

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

    private void validateSnapshot(final VirtualDataSource expected, final Path directory) throws IOException {
        final VirtualDataSource restored = dataSourceBuilder.build(TABLE_NAME, directory, false, false);
        try {
            final long firstLeafPath = expected.getFirstLeafPath();
            final long lastLeafPath = expected.getLastLeafPath();
            if (restored.getFirstLeafPath() != firstLeafPath || restored.getLastLeafPath() != lastLeafPath) {
                throw new IOException("Snapshot leaf range does not match the source");
            }

            // Check logical records, since compaction may change their file locations after the snapshot.
            for (final long path : new long[] {firstLeafPath, (firstLeafPath + lastLeafPath) / 2, lastLeafPath}) {
                final VirtualLeafBytes<?> leaf = expected.loadLeafRecord(path);
                if (leaf == null
                        || !leaf.equals(restored.loadLeafRecord(path))
                        || !leaf.equals(restored.loadLeafRecord(leaf.keyBytes()))
                        || restored.findKey(leaf.keyBytes()) != path) {
                    throw new IOException("Snapshot leaf does not match the source at path " + path);
                }
            }

            final long lastChunkId = VirtualHashChunk.lastChunkIdForPaths(lastLeafPath, expected.getHashChunkHeight());
            for (final long chunkId : new long[] {0, lastChunkId / 2, lastChunkId}) {
                final VirtualHashChunk expectedChunk = expected.loadHashChunk(chunkId);
                final VirtualHashChunk restoredChunk = restored.loadHashChunk(chunkId);
                if (expectedChunk == null || restoredChunk == null || expectedChunk.path() != restoredChunk.path()) {
                    throw new IOException("Snapshot hash chunk does not match the source: " + chunkId);
                }
                for (int index = 0; index < VirtualHashChunk.getChunkSize(expected.getHashChunkHeight()); index++) {
                    if (!Objects.equals(expectedChunk.getHashAtIndex(index), restoredChunk.getHashAtIndex(index))) {
                        throw new IOException("Snapshot hash does not match the source in chunk " + chunkId);
                    }
                }
            }
        } finally {
            restored.close();
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
