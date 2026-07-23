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
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LongListSnapshotTopologyBenchmark {

    private static final int FILE_HEADER_SIZE = Integer.BYTES + Long.BYTES;
    private static final int LONG_LIST_COUNT = 3;
    private static final int LEAF_COUNT = 104_857_600;
    private static final long FIRST_LEAF_PATH = LEAF_COUNT - 1L;
    private static final long LAST_LEAF_PATH = LEAF_COUNT * 2L - 2;

    @Param({"LongListHeap", "LongListOffHeap", "LongListSegment", "LongListDisk", "LongListDiskSegment"})
    public String listImpl;

    @Param({"1", "3", "16"})
    public int threadsPerLongList;

    private final AtomicInteger snapshotIndex = new AtomicInteger();

    private Path rootDir;
    private Path leafSnapshotFile;
    private Path bucketSnapshotFile;
    private Path hashSnapshotFile;
    private LongList leafSource;
    private LongList bucketSource;
    private LongList hashSource;
    private ThreadPoolExecutor callerExecutor;
    private int longsPerChunk;
    private boolean measurementIteration;
    private CompletionTimes completionTimes;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        rootDir = Files.createTempDirectory("LongListSnapshotTopologyBenchmark");
        final FileSystemManager fileSystemManager = new FileSystemManager(rootDir);
        final MerkleDbConfig configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build().getConfigData(MerkleDbConfig.class);
        longsPerChunk = configuration.longListChunkSize();

        final int minimumBuckets =
                Math.toIntExact(configuration.initialCapacity() / configuration.goodAverageBucketEntryCount());
        final long bucketCount = Math.max(Integer.highestOneBit(minimumBuckets) * 2L, 2);
        final long hashCount =
                VirtualHashChunk.lastChunkIdForPaths(LAST_LEAF_PATH, configuration.hashChunkHeight()) + 1;

        leafSource = createLongList(LAST_LEAF_PATH + 1, configuration, fileSystemManager);
        bucketSource = createLongList(bucketCount, configuration, fileSystemManager);
        hashSource = createLongList(hashCount, configuration, fileSystemManager);

        populate(leafSource, FIRST_LEAF_PATH, LAST_LEAF_PATH);
        populate(bucketSource, 0, bucketCount - 1);
        populate(hashSource, 0, hashCount - 1);

        callerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(LONG_LIST_COUNT);
        callerExecutor.prestartAllCoreThreads();
    }

    @Setup(Level.Iteration)
    public void setupIteration(final IterationParams iterationParams) {
        measurementIteration = iterationParams.getType() == IterationType.MEASUREMENT;
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        final int index = snapshotIndex.getAndIncrement();
        leafSnapshotFile = rootDir.resolve("leaf-" + index + ".ll");
        bucketSnapshotFile = rootDir.resolve("bucket-" + index + ".ll");
        hashSnapshotFile = rootDir.resolve("hash-" + index + ".ll");
    }

    @Benchmark
    public void writeSnapshotShape() throws Exception {
        final long start = System.nanoTime();
        final Completion leafCompletion;
        final Completion bucketCompletion;
        final Completion hashCompletion;
        try (final ExecutorService writerExecutor =
                Executors.newFixedThreadPool(threadsPerLongList * LONG_LIST_COUNT)) {
            final List<Future<Completion>> futures = callerExecutor.invokeAll(List.of(
                    () -> write(leafSource, leafSnapshotFile, writerExecutor, start),
                    () -> write(bucketSource, bucketSnapshotFile, writerExecutor, start),
                    () -> write(hashSource, hashSnapshotFile, writerExecutor, start)));
            leafCompletion = get(futures.get(0));
            bucketCompletion = get(futures.get(1));
            hashCompletion = get(futures.get(2));
        }
        completionTimes = new CompletionTimes(
                leafCompletion.nanos(), bucketCompletion.nanos(), hashCompletion.nanos(), System.nanoTime() - start);
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws IOException {
        try {
            validateSnapshot(leafSource, leafSnapshotFile);
            validateSnapshot(bucketSource, bucketSnapshotFile);
            validateSnapshot(hashSource, hashSnapshotFile);
            if (measurementIteration) {
                printCompletionTimes();
            }
        } finally {
            Files.deleteIfExists(leafSnapshotFile);
            Files.deleteIfExists(bucketSnapshotFile);
            Files.deleteIfExists(hashSnapshotFile);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        try {
            if (callerExecutor != null) {
                callerExecutor.close();
            }
            closeSources();
        } finally {
            FileUtils.deleteDirectory(rootDir);
        }
    }

    private LongList createLongList(
            final long capacity, final MerkleDbConfig configuration, final FileSystemManager fileSystemManager) {
        return switch (listImpl) {
            case "LongListHeap" -> new LongListHeap(capacity, configuration);
            case "LongListOffHeap" -> new LongListOffHeap(capacity, configuration);
            case "LongListSegment" -> new LongListSegment(capacity, configuration);
            case "LongListDisk" -> new LongListDisk(capacity, configuration, fileSystemManager);
            case "LongListDiskSegment" -> new LongListDiskSegment(capacity, configuration, fileSystemManager);
            default -> throw new IllegalArgumentException("Unknown LongList implementation: " + listImpl);
        };
    }

    private void populate(final LongList source, final long minValidIndex, final long maxValidIndex) {
        source.updateValidRange(minValidIndex, maxValidIndex);
        source.put(minValidIndex, minValidIndex + 1);
        final long firstChunkBoundary = (minValidIndex / longsPerChunk + 1) * longsPerChunk;
        for (long index = firstChunkBoundary; index <= maxValidIndex; index += longsPerChunk) {
            source.put(index, index + 1);
        }
        source.put(maxValidIndex, maxValidIndex + 1);
    }

    private Completion write(
            final LongList source, final Path snapshotFile, final ExecutorService writerExecutor, final long start)
            throws IOException {
        source.writeToFile(snapshotFile, writerExecutor, threadsPerLongList);
        return new Completion(System.nanoTime() - start);
    }

    private static Completion get(final Future<Completion> future) throws Exception {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    private void validateSnapshot(final LongList source, final Path snapshotFile) throws IOException {
        final long minValidIndex = source.getMinValidIndex();
        final long maxValidIndex = source.size() - 1;
        final long expectedFileSize = FILE_HEADER_SIZE + (maxValidIndex - minValidIndex + 1) * Long.BYTES;
        if (Files.size(snapshotFile) != expectedFileSize) {
            throw new IOException("Unexpected snapshot size: " + Files.size(snapshotFile));
        }

        try (final FileChannel channel = FileChannel.open(snapshotFile, StandardOpenOption.READ)) {
            validateValue(channel, minValidIndex, minValidIndex, minValidIndex + 1);
            final long firstChunkBoundary = (minValidIndex / longsPerChunk + 1) * longsPerChunk;
            for (long index = firstChunkBoundary; index <= maxValidIndex; index += longsPerChunk) {
                validateValue(channel, minValidIndex, index, index + 1);
            }
            validateValue(channel, minValidIndex, maxValidIndex, maxValidIndex + 1);
        }
    }

    private static void validateValue(
            final FileChannel channel, final long minValidIndex, final long index, final long expected)
            throws IOException {
        final long fileOffset = FILE_HEADER_SIZE + (index - minValidIndex) * Long.BYTES;
        final long value = readLong(channel, fileOffset);
        if (value != expected) {
            throw new IOException("Unexpected value at index " + index + ": " + value);
        }
    }

    private void printCompletionTimes() {
        final long firstCompletion = Math.min(
                completionTimes.leafNanos(), Math.min(completionTimes.bucketNanos(), completionTimes.hashNanos()));
        final long lastCompletion = Math.max(
                completionTimes.leafNanos(), Math.max(completionTimes.bucketNanos(), completionTimes.hashNanos()));
        System.out.printf(
                Locale.ROOT,
                "LONG_LIST_TOPOLOGY_TAIL\t%s\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f%n",
                listImpl,
                threadsPerLongList,
                nanosToMillis(completionTimes.leafNanos()),
                nanosToMillis(completionTimes.bucketNanos()),
                nanosToMillis(completionTimes.hashNanos()),
                nanosToMillis(completionTimes.totalNanos()),
                nanosToMillis(lastCompletion - firstCompletion));
    }

    private void closeSources() {
        for (final LongList source : List.of(leafSource, bucketSource, hashSource)) {
            source.close();
        }
    }

    private static long readLong(final FileChannel channel, final long position) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            final int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new EOFException("Unexpected end of snapshot file");
            }
        }
        buffer.flip();
        return buffer.getLong();
    }

    private static double nanosToMillis(final long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Completion(long nanos) {}

    private record CompletionTimes(long leafNanos, long bucketNanos, long hashNanos, long totalNanos) {}
}
