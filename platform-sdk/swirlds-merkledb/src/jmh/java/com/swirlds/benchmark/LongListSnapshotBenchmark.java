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
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LongListSnapshotBenchmark {

    private static final int FILE_HEADER_SIZE = Integer.BYTES + Long.BYTES;

    @Param({"LongListHeap", "LongListOffHeap", "LongListSegment", "LongListDisk", "LongListDiskSegment"})
    public String listImpl;

    @Param({"1", "3", "16"})
    public int threadsPerLongList;

    @Param({"104857600"})
    public int listSize;

    private final AtomicInteger snapshotIndex = new AtomicInteger();

    private Path rootDir;
    private Path snapshotFile;
    private LongList source;
    private ExecutorService executor;
    private int longsPerChunk;
    private long expectedFileSize;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        rootDir = Files.createTempDirectory("LongListSnapshotBenchmark");
        final FileSystemManager fileSystemManager = new FileSystemManager(rootDir);
        final MerkleDbConfig configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build().getConfigData(MerkleDbConfig.class);
        longsPerChunk = configuration.longListChunkSize();
        source = switch (listImpl) {
            case "LongListHeap" -> new LongListHeap(listSize, configuration);
            case "LongListOffHeap" -> new LongListOffHeap(listSize, configuration);
            case "LongListSegment" -> new LongListSegment(listSize, configuration);
            case "LongListDisk" -> new LongListDisk(listSize, configuration, fileSystemManager);
            case "LongListDiskSegment" -> new LongListDiskSegment(listSize, configuration, fileSystemManager);
            default -> throw new IllegalArgumentException("Unknown LongList implementation: " + listImpl);
        };

        source.updateValidRange(0, listSize - 1L);
        for (int index = 0; index < listSize; index += longsPerChunk) {
            source.put(index, index + 1L);
        }
        source.put(listSize - 1L, listSize);

        if (threadsPerLongList > 1) {
            executor = Executors.newFixedThreadPool(threadsPerLongList);
        }
        expectedFileSize = FILE_HEADER_SIZE + (source.size() - source.getMinValidIndex()) * Long.BYTES;
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        snapshotFile = rootDir.resolve("snapshot-" + snapshotIndex.getAndIncrement() + ".ll");
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
            if (Files.size(snapshotFile) != expectedFileSize) {
                throw new IOException("Unexpected snapshot size: " + Files.size(snapshotFile));
            }

            try (final FileChannel channel = FileChannel.open(snapshotFile, StandardOpenOption.READ)) {
                for (int index = 0; index < listSize; index += longsPerChunk) {
                    final long value = readLong(channel, FILE_HEADER_SIZE + (long) index * Long.BYTES);
                    if (value != index + 1L) {
                        throw new IOException("Unexpected value at index " + index + ": " + value);
                    }
                }
                final long value = readLong(channel, FILE_HEADER_SIZE + (long) (listSize - 1) * Long.BYTES);
                if (value != listSize) {
                    throw new IOException("Unexpected value at index " + (listSize - 1) + ": " + value);
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
        FileUtils.deleteDirectory(rootDir);
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
}
