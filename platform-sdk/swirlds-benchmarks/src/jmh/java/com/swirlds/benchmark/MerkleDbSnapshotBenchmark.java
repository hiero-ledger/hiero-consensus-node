// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.awaitility.Awaitility.await;

import com.swirlds.benchmark.reconnect.StateBuilder;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/// Measures complete snapshots of a state containing `numFiles * numRecords` leaves.
/// Fixture generation is outside the measured operation.
@Fork(1)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(MILLISECONDS)
public class MerkleDbSnapshotBenchmark extends VirtualMapBaseBench {

    private static final String TABLE_NAME = "state";

    @Param({"false", "true"})
    public boolean useDiskIndices;

    @Param({"1", "4"})
    public int threadsPerLongList;

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
                .withValue("merkleDb.useDiskIndices", useDiskIndices)
                .withValue("merkleDb.longListWriteThreads", threadsPerLongList)
                .withOrdinal(Integer.MAX_VALUE));
    }

    @Override
    protected void onTrialSetup() {
        super.onTrialSetup();

        final MerkleDbConfig merkleDbConfig = getConfig(MerkleDbConfig.class);
        dataSourceBuilder =
                new MerkleDbDataSourceBuilder(configuration, fileSystemManager, merkleDbConfig.initialCapacity());

        try {
            final Path fixtureDirectory = fixtureDirectory(merkleDbConfig);
            createFixtureIfNeeded(fixtureDirectory);
            source = dataSourceBuilder.build(TABLE_NAME, fixtureDirectory, false, false);
            populateHashChunkCache(merkleDbConfig);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected void onInvocationSetup() {
        super.onInvocationSetup();
        snapshotDirectory = getBenchDir().resolve("snapshot-output");
        // Remove output left by an interrupted run.
        Utils.deleteRecursively(snapshotDirectory);
    }

    @Benchmark
    public Path snapshot() {
        return dataSourceBuilder.snapshot(snapshotDirectory, source);
    }

    @Override
    protected void onInvocationTearDown() throws Exception {
        try {
            // Drain outside the timed method so pending writes do not accumulate between invocations.
            forceSnapshotFiles(snapshotDirectory);
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
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> MerkleDbDataSourceBuilder.getCountOfOpenDatabases() == 0);
        } finally {
            super.onTrialTearDown();
        }
    }

    /// Separates reusable fixtures by the settings that determine their contents and layout.
    ///
    /// @param merkleDbConfig database settings used to build the fixture
    /// @return fixture path inside the benchmark directory
    private Path fixtureDirectory(final MerkleDbConfig merkleDbConfig) {
        final long stateSize = Math.multiplyExact((long) numFiles, numRecords);
        // The framework keeps this fixture across trials when benchmark.saveDataDirectory is enabled.
        return getBenchDir()
                .resolve("fixture-%d-k%d-r%d-cap%d-h%d"
                        .formatted(
                                stateSize,
                                keySize,
                                recordSize,
                                merkleDbConfig.initialCapacity(),
                                merkleDbConfig.hashChunkHeight()));
    }

    /// Builds and flushes the state once, publishing the fixture only after its snapshot completes.
    ///
    /// @param fixtureDirectory reusable fixture location
    /// @throws IOException if fixture files cannot be written
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
                forceSnapshotFiles(directory);
            });
        } finally {
            mapReference.get().release();
        }
    }

    /// Loads cacheable hash chunks so each snapshot includes the hash-cache flush work.
    ///
    /// @param merkleDbConfig database settings defining the cache limit
    /// @throws IOException if a hash chunk cannot be read
    private void populateHashChunkCache(final MerkleDbConfig merkleDbConfig) throws IOException {
        final long lastChunkId =
                VirtualHashChunk.lastChunkIdForPaths(source.getLastLeafPath(), source.getHashChunkHeight());
        final long cachedChunkCount = Math.min((long) merkleDbConfig.hashChunkCacheThreshold(), lastChunkId + 1);
        for (long chunkId = 0; chunkId < cachedChunkCount; chunkId++) {
            source.loadHashChunk(chunkId);
        }
        logger.info("Loaded {} hash chunks into the snapshot source cache", cachedChunkCount);
    }

    /// Waits for file writes to reach storage so they do not carry over into the next measurement.
    ///
    /// @param directory snapshot directory whose files must be flushed
    /// @throws IOException if a file cannot be opened or flushed
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

    static void main() throws Exception {
        // If a larger heap is needed, set it in the IDE run configuration VM options.
        new Runner(new OptionsBuilder()
                        .include(MerkleDbSnapshotBenchmark.class.getSimpleName())
                        .forks(0)
                        .build())
                .run();
    }
}
