// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.blocks.BlockItemGeneratorUtil.generateBlockItems;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark to demonstrate that GZIP compression level is the primary performance factor.
 * This proves that compression dominates CPU time, not buffering strategies.
 * <p>
 * Tests three compression levels:
 * - Level 1: BEST_SPEED (fastest, larger files)
 * - Level 6: DEFAULT (balanced - what FileBlockItemWriter uses)
 * - Level 9: BEST_COMPRESSION (slowest, smallest files)
 * <p>
 * Expected results: Level 1 should be 2-3x faster than Level 9,
 * proving compression is the bottleneck, not buffering.
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 5)
public class FileBlockItemWriterCompressionBenchmark {

    /**
     * GZIP compression level:
     * - 1: BEST_SPEED (fastest compression, larger file)
     * - 6: DEFAULT (balanced - current FileBlockItemWriter setting)
     * - 9: BEST_COMPRESSION (slowest compression, smallest file)
     */
    @Param({"1", "6", "9"})
    private int compressionLevel;

    /**
     * Number of items per block.
     */
    @Param({"2000"})
    private int itemsPerBlock;

    /**
     * Average size of each block item in bytes.
     */
    @Param({"2000"})
    private int avgItemSizeBytes;

    private Path tempDir;
    private List<byte[]> serializedItems;
    private long blockNumber;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"com.hedera.node.app.blocks.FileBlockItemWriterCompressionBenchmark"});
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        tempDir = Files.createTempDirectory("block-benchmark-compression-");
        serializedItems = generateBlockItems(itemsPerBlock, avgItemSizeBytes);
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        blockNumber = 0;
    }

    @TearDown(Level.Trial)
    public void teardownTrial() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
            }
        }
    }

    @TearDown(Level.Invocation)
    public void teardownInvocation() throws IOException {
        Path blockDir = tempDir.resolve("blocks");
        if (Files.exists(blockDir)) {
            try (Stream<Path> paths = Files.walk(blockDir)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
            }
        }
    }

    /**
     * Benchmark complete block write with different compression levels.
     * This simulates the full FileBlockItemWriter flow but with configurable compression.
     */
    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void writeBlockWithCompression(@NonNull final Blackhole blackhole) throws IOException {
        // Phase 1: Collect data in buffer (simulating write phase)
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(10 * 1024 * 1024);
        for (byte[] itemBytes : serializedItems) {
            buffer.write(itemBytes);
        }
        byte[] uncompressedData = buffer.toByteArray();

        // Phase 2: Compress and write with specified compression level
        Path blockDir = tempDir.resolve("blocks");
        Files.createDirectories(blockDir);
        Path blockFile = blockDir.resolve(String.format("%036d.blk.gz", blockNumber++));

        try (OutputStream fileOut = Files.newOutputStream(blockFile);
                BufferedOutputStream buffered = new BufferedOutputStream(fileOut, 2 * 1024 * 1024);
                GZIPOutputStream gzip = new ConfigurableGZIPOutputStream(buffered, compressionLevel, 1024 * 1024)) {
            gzip.write(uncompressedData);
        }

        blackhole.consume(blockFile);
    }

    /**
     * Benchmark ONLY the compression phase to isolate compression time.
     * This proves compression is the bottleneck.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void compressionOnly(@NonNull final Blackhole blackhole) throws IOException {
        // Collect data in buffer
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(10 * 1024 * 1024);
        for (byte[] itemBytes : serializedItems) {
            buffer.write(itemBytes);
        }
        byte[] uncompressedData = buffer.toByteArray();

        // Measure ONLY compression time (write to in-memory buffer)
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new ConfigurableGZIPOutputStream(compressed, compressionLevel, 1024 * 1024)) {
            gzip.write(uncompressedData);
        }

        blackhole.consume(compressed.size());
    }

    /**
     * Custom GZIPOutputStream that allows setting compression level.
     */
    private static class ConfigurableGZIPOutputStream extends GZIPOutputStream {
        public ConfigurableGZIPOutputStream(OutputStream out, int compressionLevel, int bufferSize) throws IOException {
            super(out, bufferSize);
            // Set compression level on the Deflater
            def.setLevel(compressionLevel);
        }
    }
}
