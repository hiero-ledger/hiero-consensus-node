// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

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
import java.io.IOException;
import java.nio.channels.FileChannel;
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

/** Measures writes of a dense leaf index, populated once per trial outside the measured operation. */
@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LongListSnapshotBenchmark {

    @Param({"LongListHeap", "LongListOffHeap", "LongListSegment", "LongListDisk", "LongListDiskSegment"})
    public String listImpl;

    @Param({"1", "4"})
    public int threadsPerLongList;

    /** Number of leaves represented by the valid index range {@code [N - 1, 2N - 2]}. */
    @Param({"10000000"})
    public long leafCount;

    @Param({"1048576"})
    public int longListChunkSize;

    @Param({"build/tmp/long-list-snapshot-benchmark"})
    public String workDir;

    /** Compare each snapshot with a sequential write during untimed cleanup. */
    @Param({"false"})
    public boolean verify;

    private Path trialDirectory;
    private Path snapshotFile;
    private Path verificationFile;
    private LongList source;
    private ExecutorService executor;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        final Path directory = Files.createDirectories(Path.of(workDir));
        trialDirectory = Files.createTempDirectory(directory, "trial-");
        snapshotFile = trialDirectory.resolve("snapshot.ll");
        final FileSystemManager fileSystemManager = new FileSystemManager(trialDirectory);
        final MerkleDbConfig configuration = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(MerkleDbConfig_.LONG_LIST_CHUNK_SIZE, Integer.toString(longListChunkSize))
                .build()
                .getConfigData(MerkleDbConfig.class);

        // Leaf paths occupy the second half of the tree's path range.
        final long capacity = leafCount * 2;
        source = switch (listImpl) {
            case "LongListHeap" -> new LongListHeap(capacity, configuration);
            case "LongListOffHeap" -> new LongListOffHeap(capacity, configuration);
            case "LongListSegment" -> new LongListSegment(capacity, configuration);
            case "LongListDisk" -> new LongListDisk(capacity, configuration, fileSystemManager);
            case "LongListDiskSegment" -> new LongListDiskSegment(capacity, configuration, fileSystemManager);
            default -> throw new IllegalArgumentException("Unknown LongList implementation: " + listImpl);
        };
        final long firstLeafPath = leafCount - 1;
        source.updateValidRange(firstLeafPath, leafCount * 2 - 2);
        for (long index = 0; index < leafCount; index++) {
            source.put(firstLeafPath + index, DataFileCommon.dataLocation(0, index + 1));
        }

        if (verify) {
            verificationFile = trialDirectory.resolve("sequential.ll");
            source.writeToFile(verificationFile);
        }
        // Finish setup writes, including disk-backed source data, before measuring snapshots.
        try (final Stream<Path> files = Files.walk(trialDirectory)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                forceFile(file);
            }
        }
        if (threadsPerLongList > 1) {
            executor = Executors.newFixedThreadPool(threadsPerLongList);
        }
    }

    @Benchmark
    public void writeToFile() throws IOException {
        source.writeToFile(snapshotFile, executor, threadsPerLongList);
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws IOException {
        try {
            // Drain outside the timed method so pending writes do not accumulate between invocations.
            forceFile(snapshotFile);
            if (verify) {
                final long mismatch = Files.mismatch(verificationFile, snapshotFile);
                if (mismatch >= 0) {
                    throw new IOException("Snapshot differs from sequential write at byte " + mismatch);
                }
            }
        } finally {
            Files.deleteIfExists(snapshotFile);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        try {
            if (executor != null) {
                executor.close();
            }
            if (source != null) {
                source.close();
            }
        } finally {
            if (trialDirectory != null) {
                FileUtils.deleteDirectory(trialDirectory);
            }
        }
    }

    private static void forceFile(final Path file) throws IOException {
        try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
