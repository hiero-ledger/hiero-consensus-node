// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListDisk;
import com.swirlds.merkledb.collections.LongListDiskSegment;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.collections.LongListSegment;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.file.FileUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks snapshot writes from a dense leaf-index fixture. The fixture is created once per leaf count and reused
 * across JMH forks, keeping fixture generation outside the measured operation.
 */
@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LongListSnapshotBenchmark {

    /** Controls the source-file cache only for the focused {@link LongListDisk} diagnostic. */
    public enum DiskCacheState {
        UNCHANGED,
        WARM,
        COLD
    }

    // Mirrors the LongList v3 header: format version followed by minimum valid index.
    private static final int LONG_LIST_FILE_FORMAT_VERSION = 3;
    private static final int LONG_LIST_FILE_HEADER_SIZE = Integer.BYTES + Long.BYTES;
    // Unmeasured fixture creation batches one default 1,048,576-long chunk per write.
    private static final int FIXTURE_WRITE_BUFFER_SIZE = 8 * 1024 * 1024;

    @Param({"LongListHeap", "LongListOffHeap", "LongListSegment", "LongListDisk", "LongListDiskSegment"})
    public String listImpl;

    @Param({"1", "2", "8", "16", "32"})
    public int threadsPerLongList;

    /** Number of leaves represented by the valid index range {@code [N - 1, 2N - 2]}. */
    @Param({"10000000"})
    public long leafCount;

    @Param({"1048576"})
    public int longListChunkSize;

    /** Shared directory used to reuse the fixture across JMH forks. */
    @Param({"build/tmp/long-list-snapshot-benchmark"})
    public String workDir;

    /** Enables a complete byte-for-byte comparison for smoke runs. */
    @Param({"false"})
    public boolean verify;

    @Param({"UNCHANGED"})
    public DiskCacheState diskCacheState;

    private Path fixtureFile;
    private Path trialDirectory;
    private Path snapshotFile;
    private Path diskSourceFile;
    private LongList source;
    private ExecutorService executor;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        final Path sharedDirectory = Path.of(workDir).toAbsolutePath().normalize();
        Files.createDirectories(sharedDirectory);

        // Chunk size is not stored in the snapshot, so one fixture works for every chunk configuration.
        fixtureFile = sharedDirectory.resolve("leaf-index-" + leafCount + ".ll");
        createFixtureIfMissing(fixtureFile);

        trialDirectory = Files.createTempDirectory(sharedDirectory, "trial-");
        final FileSystemManager fileSystemManager = new FileSystemManager(trialDirectory);
        final MerkleDbConfig configuration = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(MerkleDbConfig_.LONG_LIST_CHUNK_SIZE, Integer.toString(longListChunkSize))
                .build()
                .getConfigData(MerkleDbConfig.class);

        // Match production leaf-index capacity: virtual paths can span twice the configured key count.
        final long capacity = Math.multiplyExact(configuration.maxNumOfKeys(), 2);
        // N leaf entries at paths N - 1 through 2N - 2 give the list an exclusive size of 2N - 1.
        final long listSize = Math.subtractExact(Math.multiplyExact(leafCount, 2), 1);
        if (listSize > capacity) {
            throw new IllegalArgumentException(
                    "Leaf index size " + listSize + " exceeds production capacity " + capacity);
        }

        snapshotFile = trialDirectory.resolve("snapshot.ll");
        source = createSource(fixtureFile, capacity, configuration, fileSystemManager);
        if (diskCacheState != DiskCacheState.UNCHANGED) {
            if (!(source instanceof LongListDisk)) {
                throw new IllegalArgumentException("Disk cache state can only be controlled for LongListDisk");
            }
            diskSourceFile = findDiskSourceFile(fileSystemManager.getTempPath());
            // Keep source durability identical so the diagnostic changes only its cache residency.
            try (final FileChannel channel = FileChannel.open(diskSourceFile, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
        if (threadsPerLongList > 1) {
            executor = Executors.newFixedThreadPool(threadsPerLongList);
        }
    }

    @Setup(Level.Invocation)
    public void prepareDiskCache() throws IOException, InterruptedException {
        if (diskCacheState == DiskCacheState.UNCHANGED) {
            return;
        }

        if (diskCacheState == DiskCacheState.WARM) {
            warmDiskSourceFile();
        } else {
            runCommand(
                    "python3",
                    "-c",
                    "import os,sys; fd=os.open(sys.argv[1],os.O_RDONLY); "
                            + "os.posix_fadvise(fd,0,0,os.POSIX_FADV_DONTNEED); os.close(fd)",
                    diskSourceFile.toString());
        }

        final long residentBytes = Long.parseLong(
                runCommand("fincore", "--bytes", "--noheadings", "--output", "RES", diskSourceFile.toString()));
        final long sourceDataSize = Math.multiplyExact(leafCount, Long.BYTES);
        if (diskCacheState == DiskCacheState.WARM && residentBytes < sourceDataSize) {
            throw new IOException("Expected at least " + sourceDataSize + " resident bytes, found " + residentBytes);
        }
        if (diskCacheState == DiskCacheState.COLD && residentBytes != 0) {
            throw new IOException("Expected no resident bytes, found " + residentBytes);
        }
        System.out.printf("Prepared %s LongListDisk source: %,d resident bytes%n", diskCacheState, residentBytes);
    }

    @Benchmark
    public void writeToFile() throws IOException {
        if (threadsPerLongList == 1) {
            source.writeToFile(snapshotFile);
        } else {
            source.writeToFile(snapshotFile, executor, threadsPerLongList);
        }
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws IOException {
        try {
            if (verify) {
                final long mismatch = Files.mismatch(fixtureFile, snapshotFile);
                if (mismatch >= 0) {
                    throw new IOException("Snapshot differs from fixture at byte " + mismatch);
                }
            }
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        if (executor != null) {
            executor.close();
        }
        if (source != null) {
            source.close();
        }
        if (trialDirectory != null) {
            FileUtils.deleteDirectory(trialDirectory);
        }
    }

    private LongList createSource(
            final Path file,
            final long capacity,
            final MerkleDbConfig configuration,
            final FileSystemManager fileSystemManager)
            throws IOException {
        return switch (listImpl) {
            case "LongListHeap" -> new LongListHeap(file, capacity, configuration);
            case "LongListOffHeap" -> new LongListOffHeap(file, capacity, configuration);
            case "LongListSegment" -> new LongListSegment(file, capacity, configuration);
            case "LongListDisk" -> new LongListDisk(file, capacity, configuration, fileSystemManager);
            case "LongListDiskSegment" -> new LongListDiskSegment(file, capacity, configuration, fileSystemManager);
            default -> throw new IllegalArgumentException("Unknown LongList implementation: " + listImpl);
        };
    }

    private Path findDiskSourceFile(final Path tempDirectory) throws IOException {
        try (final Stream<Path> files = Files.walk(tempDirectory)) {
            return files.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IOException("LongListDisk backing file was not created"));
        }
    }

    private void warmDiskSourceFile() throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(FIXTURE_WRITE_BUFFER_SIZE);
        try (final FileChannel channel = FileChannel.open(diskSourceFile, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.clear();
            }
        }
    }

    private String runCommand(final String... command) throws IOException, InterruptedException {
        final Process process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IOException("Command failed: " + String.join(" ", command) + System.lineSeparator() + output);
        }
        return output;
    }

    private void createFixtureIfMissing(final Path file) throws IOException {
        if (Files.exists(file)) {
            return;
        }

        final long firstLeafPath = leafCount - 1;
        System.out.printf("Creating dense leaf-index fixture with %,d entries%n", leafCount);
        // Write the file directly so fixture creation scales to billions of entries without individual LongList puts.
        try (final FileChannel channel =
                FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            // LongList snapshots use a big-endian header and a little-endian body.
            final ByteBuffer headerBuffer = ByteBuffer.allocate(LONG_LIST_FILE_HEADER_SIZE);
            headerBuffer.putInt(LONG_LIST_FILE_FORMAT_VERSION);
            headerBuffer.putLong(firstLeafPath);
            headerBuffer.flip();
            MerkleDbFileUtils.completelyWrite(channel, headerBuffer);

            final ByteBuffer dataBuffer =
                    ByteBuffer.allocateDirect(FIXTURE_WRITE_BUFFER_SIZE).order(LITTLE_ENDIAN);
            long entriesWritten = 0;
            while (entriesWritten < leafCount) {
                dataBuffer.clear();
                final int entryCount = (int) Math.min(dataBuffer.capacity() / Long.BYTES, leafCount - entriesWritten);
                for (int index = 0; index < entryCount; index++) {
                    // Zero means missing; file 0 with offsets starting at one gives every leaf a distinct location.
                    dataBuffer.putLong(DataFileCommon.dataLocation(0, entriesWritten + index + 1));
                }
                dataBuffer.flip();
                MerkleDbFileUtils.completelyWrite(channel, dataBuffer);
                entriesWritten += entryCount;
            }

            // Prevent fixture writeback from competing with the measured snapshot writes.
            channel.force(true);
        }
    }
}
