// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.blocks.BlockItemGeneratorUtil.generateBlockItems;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriterV3;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark comparing the original FileBlockItemWriter (GZIP) with V3 (ZSTD).
 * <p>
 * This benchmark demonstrates that ZSTD compression provides significant performance
 * improvements over GZIP while maintaining similar or better compression ratios.
 * <p>
 * Expected results:
 * - V3 (ZSTD Level 1) should be 8x faster than original (GZIP Level 6)
 * - Compression ratios should be similar or better with ZSTD
 * - File sizes should be comparable or smaller with ZSTD
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 5)
public class FileBlockItemWriterV3CompressionBenchmark {

    /**
     * Auxiliary counters to track file sizes during benchmarks.
     * These will appear in the benchmark results as additional metrics.
     */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class FileSizeCounters {
        public long uncompressedBytes;
        public long compressedBytes;
        public long compressionRatioPercent;

        public void recordFileSizes(long uncompressed, long compressed) {
            this.uncompressedBytes = uncompressed;
            this.compressedBytes = compressed;
            this.compressionRatioPercent = uncompressed > 0 ? (compressed * 100) / uncompressed : 0;
        }
    }

    /**
     * Implementation to test:
     * - "original": Original implementation with GZIP compression (Level 6)
     * - "v3": V3 implementation with ZSTD compression (Level 1)
     */
    @Param({"original", "v3"})
    private String implementation;

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
    private ConfigProvider configProvider;
    private NodeInfo nodeInfo;
    private FileSystem fileSystem;

    // Pre-generated block items for consistent benchmarking
    private List<byte[]> serializedItems;
    private long blockNumber;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"com.hedera.node.app.blocks.FileBlockItemWriterV3Benchmark"});
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        // Create temporary directory for benchmark files
        tempDir = Files.createTempDirectory("block-benchmark-v3-");

        // Setup real configuration using HederaTestConfigBuilder
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.blockFileDir", tempDir.toString())
                .getOrCreateConfig();

        configProvider = () -> new VersionedConfigImpl(config, 1L);

        // Setup node info
        nodeInfo = new NodeInfoImpl(
                0, AccountID.newBuilder().accountNum(3).build(), 10, List.of(), Bytes.EMPTY, List.of(), false, null);

        fileSystem = FileSystems.getDefault();

        // Pre-generate block items for consistent benchmarking
        serializedItems = generateBlockItems(itemsPerBlock, avgItemSizeBytes);
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        blockNumber = 0;
    }

    @TearDown(Level.Trial)
    public void teardownTrial() throws IOException {
        // Clean up temporary files
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
        // Clean up block files after each invocation
        Path blockDir = tempDir.resolve("block-0.0.3");
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
     * Benchmark complete block lifecycle - measures throughput and average time.
     * Compares:
     * - Original: GZIP compression (Level 6) with triple-buffered streaming
     * - V3: ZSTD compression (Level 1) with triple-buffered streaming
     */
    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void writeCompleteBlock(@NonNull final Blackhole blackhole, @NonNull final FileSizeCounters counters)
            throws IOException {
        BlockItemWriter writer = createWriter();

        // Calculate uncompressed size
        long uncompressedSize =
                serializedItems.stream().mapToLong(item -> item.length).sum();

        writer.openBlock(blockNumber);
        long currentBlockNumber = blockNumber++;

        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
        }

        writer.closeCompleteBlock();

        // Measure compressed file size
        Path blockDir = tempDir.resolve("block-0.0.3");
        String fileName =
                String.format("%036d.blk.%s", currentBlockNumber, "original".equals(implementation) ? "gz" : "zst");
        Path blockFile = blockDir.resolve(fileName);

        if (Files.exists(blockFile)) {
            long compressedSize = Files.size(blockFile);
            counters.recordFileSizes(uncompressedSize, compressedSize);
        }

        blackhole.consume(writer);
    }

    /**
     * Benchmark that measures compression ratios and file sizes.
     * This provides detailed metrics on how well each compression algorithm compresses the data.
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void measureCompressionRatio(@NonNull final Blackhole blackhole, @NonNull final FileSizeCounters counters)
            throws IOException {
        BlockItemWriter writer = createWriter();

        // Calculate uncompressed size
        long uncompressedSize =
                serializedItems.stream().mapToLong(item -> item.length).sum();

        writer.openBlock(blockNumber);
        long currentBlockNumber = blockNumber++;

        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
        }

        writer.closeCompleteBlock();

        // Measure compressed file size
        Path blockDir = tempDir.resolve("block-0.0.3");
        String fileName =
                String.format("%036d.blk.%s", currentBlockNumber, "original".equals(implementation) ? "gz" : "zst");
        Path blockFile = blockDir.resolve(fileName);

        if (Files.exists(blockFile)) {
            long compressedSize = Files.size(blockFile);
            counters.recordFileSizes(uncompressedSize, compressedSize);
        }

        blackhole.consume(writer);
    }

    /**
     * Benchmark only the write operations (excluding open/close overhead).
     * Measures average time per write operation.
     * This isolates the compression performance differences.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void writeItemsOnly(@NonNull final Blackhole blackhole) {
        BlockItemWriter writer = createWriter();
        writer.openBlock(blockNumber++);

        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
        }

        // Don't close to isolate write performance
        blackhole.consume(writer);
    }

    /**
     * Creates the appropriate writer implementation based on the benchmark parameter.
     */
    private BlockItemWriter createWriter() {
        return switch (implementation) {
            case "original" -> new FileBlockItemWriter(configProvider, nodeInfo, fileSystem);
            case "v3" -> new FileBlockItemWriterV3(configProvider, nodeInfo, fileSystem);
            default -> throw new IllegalStateException("Unknown implementation: " + implementation);
        };
    }
}
