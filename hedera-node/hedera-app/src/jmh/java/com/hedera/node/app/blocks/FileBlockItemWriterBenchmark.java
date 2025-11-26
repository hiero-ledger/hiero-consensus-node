// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriterV2;
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

import static com.hedera.node.app.blocks.BlockItemGeneratorUtil.generateBlockItems;

/**
 * JMH Benchmark for FileBlockItemWriter to analyze and optimize block writing performance.
 * This benchmark compares the original implementation against the V2 implementation with in-memory buffering.
 * <p>
 * This benchmark measures:
 * 1. Throughput of writing blocks (both synchronous compression)
 * 2. Impact of different item sizes on performance (25 KB to 500 MB blocks)
 * 3. Effectiveness of in-memory buffering vs direct GZIP streaming
 * 4. Compression ratios with realistic protobuf-encoded transaction and state data
 * <p>
 * Available benchmarks:
 * - writeCompleteBlock: Measures throughput and average time for complete block lifecycle
 * - measureCompressionRatio: Measures compression ratios and file sizes
 * - writeItemsOnly: Measures write-only performance (excluding open/close overhead)
 * <p>
 * NOTE: This benchmark tests 2 implementations * 5 item counts * 5 item sizes = 50 parameter combinations.
 * Each benchmark method runs with all 50 combinations. Total run time can be significant (2-4 hours).
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 5)
public class FileBlockItemWriterBenchmark {

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
     * - "original": Original synchronous implementation (triple-buffered GZIP streaming)
     * - "v2": V2 implementation (in-memory ByteArrayOutputStream buffer with synchronous compression)
     */
    @Param({"original", "v2"})
    private String implementation;

    /**
     * Number of items per block.
     * Block size ranges from 25 KB (50 * 500) to 500 MB (10000 * 50000).
     * Examples:
     * - Small: 50 items * 500 bytes = 25 KB
     * - Typical: 500 items * 2000 bytes = 1 MB
     * - Medium: 2000 items * 5000 bytes = 10 MB
     * - Large: 5000 items * 20000 bytes = 100 MB
     * - Very Large: 10000 items * 50000 bytes = 500 MB
     */
    @Param({"50", "500", "2000", "5000", "10000"})
    private int itemsPerBlock;

    /**
     * Average size of each block item in bytes.
     * Combined with itemsPerBlock to create various block sizes.
     */
    @Param({"500", "2000", "5000", "20000", "50000"})
    private int avgItemSizeBytes;

    private Path tempDir;
    private ConfigProvider configProvider;
    private NodeInfo nodeInfo;
    private FileSystem fileSystem;

    // Pre-generated block items for consistent benchmarking
    private List<byte[]> serializedItems;
    private long blockNumber;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"com.hedera.node.app.blocks.FileBlockItemWriterBenchmark"});
    }

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        // Create temporary directory for benchmark files
        tempDir = Files.createTempDirectory("block-benchmark-");

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
     * Both implementations use synchronous compression, so this directly compares:
     * - Original: Direct GZIP streaming with triple buffering
     * - V2: In-memory buffer followed by GZIP compression
     */
    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void writeCompleteBlock(@NonNull final Blackhole blackhole) {
        BlockItemWriter writer = createWriter();

        writer.openBlock(blockNumber++);

        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
        }

        writer.closeCompleteBlock();

        blackhole.consume(writer);
    }

    /**
     * Measure compression ratios by checking file sizes after compression.
     * Both implementations complete compression synchronously in closeCompleteBlock.
     * Run this separately to see compression ratios without affecting throughput measurements.
     */
//    @Benchmark
//    @BenchmarkMode(Mode.SingleShotTime)
//    @OutputTimeUnit(TimeUnit.MILLISECONDS)
//    @Warmup(iterations = 0)
//    @Measurement(iterations = 3)
//    public void measureCompressionRatio(@NonNull final Blackhole blackhole, @NonNull final FileSizeCounters counters) {
//        BlockItemWriter writer = createWriter();
//        long currentBlockNum = blockNumber++;
//
//        writer.openBlock(currentBlockNum);
//
//        // Calculate uncompressed size
//        long uncompressedSize = 0;
//        for (byte[] itemBytes : serializedItems) {
//            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
//            uncompressedSize += itemBytes.length;
//        }
//
//        writer.closeCompleteBlock();
//
//        // Record file sizes for the report
//        try {
//            Path blockFile = tempDir.resolve("block-0.0.3").resolve(String.format("%036d.blk.gz", currentBlockNum));
//            long compressedSize = Files.exists(blockFile) ? Files.size(blockFile) : 0;
//            counters.recordFileSizes(uncompressedSize, compressedSize);
//        } catch (IOException e) {
//            // Ignore - just won't record file sizes
//        }
//
//        blackhole.consume(writer);
//    }

    /**
     * Benchmark only the write operations (excluding open/close overhead).
     * Measures average time per write operation.
     * This isolates the buffering improvements from compression changes.
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
            case "v2" -> new FileBlockItemWriterV2(configProvider, nodeInfo, fileSystem);
            default -> throw new IllegalStateException("Unknown implementation: " + implementation);
        };
    }
}
