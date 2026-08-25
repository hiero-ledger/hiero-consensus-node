// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.swirlds.merkledb.utilities.MerkleDbFileUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hiero.base.file.FileUtils;
import org.openjdk.jmh.annotations.AuxCounters;
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
 * Measures the durable {@link FileChannel} write path without LongList traversal, source reads, or data preparation in
 * the timed operation. One immutable direct buffer is reused for all requests, while every byte of the target body is
 * still written.
 */
@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FileChannelWriteBenchmark {

    // Matches the LongList v3 header: format version followed by minimum valid index.
    private static final int FILE_FORMAT_VERSION = 3;
    private static final int FILE_HEADER_SIZE = Integer.BYTES + Long.BYTES;
    // Matches the 8 MiB body of one production-default LongList chunk.
    private static final int WRITE_BUFFER_SIZE = 8 * 1024 * 1024;

    /** Eight bytes per leaf make the default value an 8 GB body. */
    @Param({"8000000000"})
    public long bodySizeBytes;

    /** Number of workers writing non-overlapping ranges of the same target file. */
    @Param({"1", "2", "8", "16", "32"})
    public int writerThreads;

    /** Directory containing the temporary target file. */
    @Param({"build/tmp/filechannel-write-benchmark"})
    public String workDir;

    /** Enables a complete content check for small smoke runs. */
    @Param({"false"})
    public boolean verify;

    private Path trialDirectory;
    private Path targetFile;
    private ByteBuffer preparedData;
    private ExecutorService executor;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        final Path sharedDirectory = Path.of(workDir).toAbsolutePath().normalize();
        Files.createDirectories(sharedDirectory);
        trialDirectory = Files.createTempDirectory(sharedDirectory, "trial-");
        targetFile = trialDirectory.resolve("file-channel-write.bin");

        // Prepare dense pseudo-random data once so the timed operation contains only target-file work.
        preparedData = ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE);
        final SplittableRandom random = new SplittableRandom(1234);
        while (preparedData.hasRemaining()) {
            preparedData.putLong(random.nextLong());
        }
        preparedData.flip();

        if (writerThreads > 1) {
            executor = Executors.newFixedThreadPool(writerThreads);
        }
    }

    @Benchmark
    public void writePreparedFile(final WriteTimings timings) throws Exception {
        try (final FileChannel channel =
                FileChannel.open(targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeHeader(channel);

            final long bodyWriteStart = System.nanoTime();
            writePreparedData(channel);
            timings.bodyWriteNanos = System.nanoTime() - bodyWriteStart;

            final long forceStart = System.nanoTime();
            channel.force(true);
            timings.forceNanos = System.nanoTime() - forceStart;
        }
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws IOException {
        try {
            if (verify) {
                verifyTargetFile();
            }
        } finally {
            Files.deleteIfExists(targetFile);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        if (executor != null) {
            executor.close();
        }
        if (trialDirectory != null) {
            FileUtils.deleteDirectory(trialDirectory);
        }
    }

    private void writeHeader(final FileChannel channel) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_SIZE);
        header.putInt(FILE_FORMAT_VERSION);
        header.putLong(0);
        header.flip();
        MerkleDbFileUtils.completelyWrite(channel, header, 0);
    }

    private void writePreparedData(final FileChannel channel) throws Exception {
        if (writerThreads == 1) {
            writeRange(channel, preparedData.duplicate(), 0, bodySizeBytes);
            return;
        }

        // Number of 8 MiB writes needed to cover the configured body size.
        final long totalWriteCount = Math.ceilDiv(bodySizeBytes, WRITE_BUFFER_SIZE);
        // Number of active workers, capped so every worker owns at least one write.
        final int taskCount = (int) Math.min(writerThreads, totalWriteCount);
        // Minimum writes per worker, used as the base size of the balanced partition.
        final long writesPerTask = totalWriteCount / taskCount;
        // Leading workers with one extra write, used to distribute the remainder.
        final long tasksWithOneMoreWrite = totalWriteCount % taskCount;

        final List<Callable<Void>> tasks = new ArrayList<>(taskCount);
        long rangeFirstWriteIndex = 0;
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            final long rangeWriteCount = writesPerTask + (taskIndex < tasksWithOneMoreWrite ? 1 : 0);
            final long rangeLastWriteExclusive = rangeFirstWriteIndex + rangeWriteCount;
            final long firstByteOffset = rangeFirstWriteIndex * WRITE_BUFFER_SIZE;
            final long lastByteOffset = Math.min(bodySizeBytes, rangeLastWriteExclusive * WRITE_BUFFER_SIZE);
            final ByteBuffer taskBuffer = preparedData.duplicate();

            tasks.add(() -> {
                writeRange(channel, taskBuffer, firstByteOffset, lastByteOffset);
                return null;
            });
            rangeFirstWriteIndex = rangeLastWriteExclusive;
        }

        for (final Future<Void> task : executor.invokeAll(tasks)) {
            task.get();
        }
    }

    private void writeRange(
            final FileChannel channel,
            final ByteBuffer taskBuffer,
            final long firstByteOffset,
            final long lastByteOffset)
            throws IOException {
        long byteOffset = firstByteOffset;
        while (byteOffset < lastByteOffset) {
            final int bytesToWrite = (int) Math.min(taskBuffer.capacity(), lastByteOffset - byteOffset);
            taskBuffer.clear();
            taskBuffer.limit(bytesToWrite);
            MerkleDbFileUtils.completelyWrite(channel, taskBuffer, FILE_HEADER_SIZE + byteOffset);
            byteOffset += bytesToWrite;
        }
    }

    private void verifyTargetFile() throws IOException {
        final long expectedFileSize = FILE_HEADER_SIZE + bodySizeBytes;
        if (Files.size(targetFile) != expectedFileSize) {
            throw new IOException("Expected file size " + expectedFileSize + ", actual " + Files.size(targetFile));
        }

        try (final FileChannel channel = FileChannel.open(targetFile, StandardOpenOption.READ)) {
            final ByteBuffer header = MerkleDbFileUtils.readFromFileChannel(channel, FILE_HEADER_SIZE);
            if (header.getInt() != FILE_FORMAT_VERSION || header.getLong() != 0) {
                throw new IOException("Unexpected file header");
            }

            final ByteBuffer actualData = ByteBuffer.allocate(WRITE_BUFFER_SIZE);
            long byteOffset = 0;
            while (byteOffset < bodySizeBytes) {
                final int bytesToRead = (int) Math.min(actualData.capacity(), bodySizeBytes - byteOffset);
                actualData.clear();
                actualData.limit(bytesToRead);
                if (MerkleDbFileUtils.completelyRead(channel, actualData, FILE_HEADER_SIZE + byteOffset)
                        != bytesToRead) {
                    throw new IOException("Could not read target data at byte " + byteOffset);
                }
                actualData.flip();

                final ByteBuffer expectedData = preparedData.asReadOnlyBuffer();
                expectedData.limit(bytesToRead);
                final int mismatch = expectedData.mismatch(actualData);
                if (mismatch >= 0) {
                    throw new IOException("Unexpected target data at byte " + (byteOffset + mismatch));
                }
                byteOffset += bytesToRead;
            }
        }
    }

    /** Phase times recorded next to JMH's total invocation time. */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class WriteTimings {
        public long bodyWriteNanos;
        public long forceNanos;
    }
}
