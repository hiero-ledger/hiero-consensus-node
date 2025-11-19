// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
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

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JMH Benchmark for FileBlockItemWriter to analyze and optimize block writing performance.
 *
 * This benchmark measures:
 * 1. Throughput of writing blocks to disk with different block sizes
 * 2. Impact of different item sizes on performance
 * 3. Effectiveness of the current buffering strategy
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 5)
public class FileBlockItemWriterBenchmark {

    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    // Number of items per block (typical blocks have ~100-200 items)
    @Param({"50", "100", "200", "500"})
    private int itemsPerBlock;

    // Average size of each block item in bytes (typical range: 100-5000 bytes)
    @Param({"500", "2000", "5000"})
    private int avgItemSizeBytes;

    private Path tempDir;
    private ConfigProvider configProvider;
    private NodeInfo nodeInfo;
    private FileSystem fileSystem;

    // Pre-generated block items for consistent benchmarking
    private List<byte[]> serializedItems;
    private long blockNumber;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {
                "com.hedera.node.app.blocks.FileBlockItemWriterBenchmark"
        });
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
                0,
                AccountID.newBuilder().accountNum(3).build(),
                10,
                List.of(),
                Bytes.EMPTY,
                List.of(),
                false,
                null
        );

        fileSystem = FileSystems.getDefault();

        // Pre-generate block items for consistent benchmarking
        generateBlockItems();
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
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
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
                paths.filter(Files::isRegularFile)
                        .forEach(path -> {
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
     * Benchmark writing a complete block with realistic data.
     * Measures both throughput (ops/sec) and average time (ms/op).
     */
    @Benchmark
    @BenchmarkMode({Mode.Throughput, Mode.AverageTime})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void writeCompleteBlock(@NonNull final Blackhole blackhole) {
        FileBlockItemWriter writer = new FileBlockItemWriter(configProvider, nodeInfo, fileSystem);

        writer.openBlock(blockNumber++);

        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
        }

        writer.closeCompleteBlock();
        blackhole.consume(writer);
    }

    /**
     * Benchmark only the write operations (excluding open/close overhead).
     * Measures average time per write operation.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void writeItemsOnly(@NonNull final Blackhole blackhole) {
        FileBlockItemWriter writer = new FileBlockItemWriter(configProvider, nodeInfo, fileSystem);
        writer.openBlock(blockNumber++);

        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
        }

        // Don't close to isolate write performance
        blackhole.consume(writer);
    }


    /**
     * Generate realistic block items for benchmarking.
     */
    private void generateBlockItems() {
        serializedItems = new ArrayList<>(itemsPerBlock);

        for (int i = 0; i < itemsPerBlock; i++) {
            BlockItem item = generateRandomBlockItem(avgItemSizeBytes);
            byte[] serialized = BlockItem.PROTOBUF.toBytes(item).toByteArray();
            serializedItems.add(serialized);
        }
    }

    /**
     * Generate a random BlockItem with approximately the specified size.
     */
    private static BlockItem generateRandomBlockItem(int targetSize) {
        // Randomly choose item type
        int type = RANDOM.nextInt(4);

        return switch (type) {
            case 0 -> generateTransactionItem(targetSize);
            case 1 -> generateStateChangeItem(targetSize);
            case 2 -> generateEventHeaderItem(targetSize);
            default -> generateRoundHeaderItem();
        };
    }

    private static BlockItem generateTransactionItem(int targetSize) {
        // Create a transaction with random data to reach target size
        byte[] txData = new byte[Math.max(100, targetSize - 100)];
        RANDOM.nextBytes(txData);

        return BlockItem.newBuilder()
                .signedTransaction(Bytes.wrap(txData))
                .build();
    }

    private static BlockItem generateStateChangeItem(int targetSize) {
        // Create state changes with realistic account data
        int numChanges = Math.max(1, targetSize / 200);
        StateChange[] changes = new StateChange[numChanges];

        for (int i = 0; i < numChanges; i++) {
            changes[i] = StateChange.newBuilder()
                    .stateId(1)
                    .build();
        }

        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .consensusTimestamp(randomTimestamp())
                        .stateChanges(changes)
                        .build())
                .build();
    }

    private static BlockItem generateEventHeaderItem(int targetSize) {
        // Create an EventCore for the event header
        // Add some extra data to eventCore to reach target size if needed
        EventCore eventCore = EventCore.newBuilder()
                .birthRound(RANDOM.nextLong(1, 1_000_000))
                .creatorNodeId(RANDOM.nextLong(0, 10))
                .timeCreated(randomTimestamp())
                .build();

        return BlockItem.newBuilder()
                .eventHeader(EventHeader.newBuilder()
                        .eventCore(eventCore)
                        .build())
                .build();
    }

    private static BlockItem generateRoundHeaderItem() {
        return BlockItem.newBuilder()
                .roundHeader(RoundHeader.newBuilder()
                        .roundNumber(RANDOM.nextLong(1_000_000))
                        .build())
                .build();
    }

    private static Timestamp randomTimestamp() {
        return Timestamp.newBuilder()
                .seconds(RANDOM.nextLong(1_700_000_000, 1_800_000_000))
                .nanos(RANDOM.nextInt(1_000_000_000))
                .build();
    }
}
