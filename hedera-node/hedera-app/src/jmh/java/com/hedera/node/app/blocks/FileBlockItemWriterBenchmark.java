// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
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
 * JMH Benchmark for FileBlockItemWriter to analyze and optimize block writing performance.
 * This benchmark measures:
 * 1. Throughput of writing blocks with async compression (realistic production behavior)
 * 2. Impact of different item sizes on performance
 * 3. Effectiveness of buffer consolidation and compression strategies
 * 4. Compression ratios with realistic protobuf-encoded transaction and state data
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

    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    // Number of items per block
    @Param({"50", "100", "200", "500"})
    private int itemsPerBlock;

    // Average size of each block item in bytes
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
     * Benchmark ASYNC pipeline behavior - measures throughput when blocks overlap.
     * This shows the benefit of async compression where Block N+1 can be
     * opened/written while Block N compresses in the background.
     * Measures how fast we can start new blocks (without waiting for previous ones).
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

        // don't wait - compression happens in background
        // to measure with waiting for compression, uncomment next line
        // writer.awaitCompressionComplete();

        blackhole.consume(writer);
    }

    /**
     * Measure compression ratios by waiting for compression and checking file sizes.
     * This is NOT the async benchmark - it waits for compression to complete.
     * Run this separately to see compression ratios without affecting async performance measurements.
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 0)
    @Measurement(iterations = 3)
    public void measureCompressionRatio(@NonNull final Blackhole blackhole, @NonNull final FileSizeCounters counters) {
        FileBlockItemWriter writer = new FileBlockItemWriter(configProvider, nodeInfo, fileSystem);
        long currentBlockNum = blockNumber++;

        writer.openBlock(currentBlockNum);

        // Calculate uncompressed size
        long uncompressedSize = 0;
        for (byte[] itemBytes : serializedItems) {
            writer.writePbjItemAndBytes(null, Bytes.wrap(itemBytes));
            uncompressedSize += itemBytes.length;
        }

        writer.closeCompleteBlock();

        // Wait for compression to complete to get accurate file size
        writer.awaitCompressionComplete();

        // Record file sizes for the report
        try {
            Path blockFile = tempDir.resolve("block-0.0.3").resolve(String.format("%036d.blk.gz", currentBlockNum));
            long compressedSize = Files.exists(blockFile) ? Files.size(blockFile) : 0;
            counters.recordFileSizes(uncompressedSize, compressedSize);
        } catch (IOException e) {
            // Ignore - just won't record file sizes
        }

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
     * Uses patterns similar to real transactions/state changes for realistic compression testing.
     */
    private void generateBlockItems() {
        serializedItems = new ArrayList<>(itemsPerBlock);

        for (int i = 0; i < itemsPerBlock; i++) {
            BlockItem item = generateRealisticBlockItem(avgItemSizeBytes, i);
            byte[] serialized = BlockItem.PROTOBUF.toBytes(item).toByteArray();
            serializedItems.add(serialized);
        }
    }

    /**
     * Generate a realistic BlockItem with approximately the specified size.
     * Uses patterns that mimic real blockchain data for accurate compression testing.
     */
    private static BlockItem generateRealisticBlockItem(int targetSize, int index) {
        // Randomly choose item type
        int type = RANDOM.nextInt(4);

        return switch (type) {
            case 0 -> generateTransactionItem(targetSize, index);
            case 1 -> generateStateChangeItem(targetSize, index);
            case 2 -> generateEventHeaderItem(index); // No targetSize needed - naturally small
            default -> generateRoundHeaderItem(index); // No targetSize needed - naturally small
        };
    }

    private static BlockItem generateTransactionItem(int targetSize, int index) {
        // Generate a REAL protobuf-serialized Hedera transaction

        // 1. Create TransactionID with realistic patterns
        TransactionID transactionID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(1000 + (index % 1000)) // Repeating account IDs
                        .build())
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(1700000000L + (index * 10)) // Sequential timestamps
                        .nanos(0)
                        .build())
                .build();

        // 2. Create TransactionBody (most common: crypto transfer)
        // Pad memo to reach target size
        String memo = "tx_" + index;
        int estimatedBaseSize = 200; // Rough estimate of other fields
        int neededMemoSize = Math.max(0, targetSize - estimatedBaseSize);
        if (neededMemoSize > 0) {
            memo = memo + "_".repeat(Math.min(neededMemoSize, 1000)); // Max 1000 chars
        }

        TransactionBody transactionBody = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .nodeAccountID(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(3 + (index % 10)) // Node accounts from small set
                        .build())
                .transactionFee(100000 + (index % 1000)) // Similar fees
                .transactionValidDuration(Duration.newBuilder().seconds(120).build())
                .memo(memo)
                .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT) // Empty transfer for benchmarking
                .build();

        // 3. Serialize TransactionBody to bytes
        Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(transactionBody);

        // 4. Create SignedTransaction with signature map
        SignedTransaction signedTransaction = SignedTransaction.newBuilder()
                .bodyBytes(bodyBytes)
                .sigMap(SignatureMap.DEFAULT) // Empty sig map for benchmarking
                .build();

        // 5. Serialize SignedTransaction to bytes
        Bytes signedTxBytes = SignedTransaction.PROTOBUF.toBytes(signedTransaction);

        // 6. Wrap in BlockItem
        return BlockItem.newBuilder().signedTransaction(signedTxBytes).build();
    }

    private static BlockItem generateStateChangeItem(int targetSize, int index) {
        int numChanges = Math.max(1, targetSize / 300);
        StateChange[] changes = new StateChange[numChanges];

        for (int i = 0; i < numChanges; i++) {
            // State ID for ACCOUNTS virtual map (common state change)
            int stateId = 100 + ((index + i) % 50); // Repeating pattern

            // Create a realistic account state value with repeating patterns
            Account accountValue = Account.newBuilder()
                    .accountId(AccountID.newBuilder()
                            .shardNum(0)
                            .realmNum(0)
                            .accountNum(1000 + ((index + i) % 5000)) // Repeating account numbers
                            .build())
                    .tinybarBalance(1000000L + ((index + i) * 100)) // Sequential balances
                    .memo("account_" + ((index + i) % 100)) // Repeating memos
                    // Most other fields default to zero
                    .build();

            // Create map update change
            MapUpdateChange mapUpdate = MapUpdateChange.newBuilder()
                    .key(MapChangeKey.newBuilder()
                            .accountIdKey(AccountID.newBuilder()
                                    .shardNum(0)
                                    .realmNum(0)
                                    .accountNum(1000 + ((index + i) % 5000))
                                    .build())
                            .build())
                    .value(MapChangeValue.newBuilder()
                            .accountValue(accountValue)
                            .build())
                    .build();

            changes[i] = StateChange.newBuilder()
                    .stateId(stateId)
                    .mapUpdate(mapUpdate)
                    .build();
        }

        return BlockItem.newBuilder()
                .stateChanges(StateChanges.newBuilder()
                        .consensusTimestamp(Timestamp.newBuilder()
                                .seconds(1700000000L + (index * 10)) // Sequential timestamps
                                .nanos(0)
                                .build())
                        .stateChanges(changes)
                        .build())
                .build();
    }

    private static BlockItem generateEventHeaderItem(int index) {
        EventCore eventCore = EventCore.newBuilder()
                .birthRound(1_000_000 + (index / 100)) // Sequential rounds
                .creatorNodeId(index % 10) // Rotating node IDs
                .timeCreated(Timestamp.newBuilder()
                        .seconds(1700000000L + (index * 10))
                        .nanos(0)
                        .build())
                .build();

        return BlockItem.newBuilder()
                .eventHeader(EventHeader.newBuilder().eventCore(eventCore).build())
                .build();
    }

    private static BlockItem generateRoundHeaderItem(int index) {
        return BlockItem.newBuilder()
                .roundHeader(RoundHeader.newBuilder()
                        .roundNumber(1_000_000 + (index / 1000)) // Sequential round numbers
                        .build())
                .build();
    }
}
