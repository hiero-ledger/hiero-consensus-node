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
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * JMH Benchmark for compression strategies used in block writing.
 * This benchmark isolates compression performance to help determine
 * the optimal compression strategy for FileBlockItemWriter.
 * Tests different GZIP compression levels:
 * - NONE: No compression (baseline)
 * - GZIP_FAST: Level 1 (fastest, worst compression)
 * - GZIP_DEFAULT: Level 6 (current implementation)
 * - GZIP_BEST: Level 9 (slowest, best compression)
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 5, time = 5)
public class CompressionBenchmark {

    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    // Test fewer parameter combinations since we're focused on compression
    @Param({"50", "200", "500"})
    private int itemsPerBlock;

    @Param({"500", "2000", "5000"})
    private int avgItemSizeBytes;

    @Param({"NONE", "GZIP_FAST", "GZIP_DEFAULT", "GZIP_BEST"})
    private String compressionType;

    // Pre-generated block items for consistent benchmarking
    private List<byte[]> serializedItems;
    private byte[] uncompressedData;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {
                "com.hedera.node.app.blocks.CompressionBenchmark"
        });
    }

    @Setup(Level.Trial)
    public void setupTrial() {
        // Generate realistic block items
        generateBlockItems();
        
        // Concatenate all items into a single byte array (simulates a block)
        int totalSize = serializedItems.stream().mapToInt(arr -> arr.length).sum();
        uncompressedData = new byte[totalSize];
        int offset = 0;
        for (byte[] item : serializedItems) {
            System.arraycopy(item, 0, uncompressedData, offset, item.length);
            offset += item.length;
        }
    }

    /**
     * Benchmark compression overhead for different strategies.
     * Measures the time to compress a complete block's worth of data.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void compressionLatency(@NonNull final Blackhole blackhole) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompressedData.length);
        OutputStream out = createCompressionStream(baos, compressionType);
        out.write(uncompressedData);
        out.close();
        
        blackhole.consume(baos.toByteArray());
    }

    /**
     * Benchmark throughput of different compression strategies.
     * Measures how many blocks per second can be compressed.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void compressionThroughput(@NonNull final Blackhole blackhole) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompressedData.length);
        OutputStream out = createCompressionStream(baos, compressionType);
        
        // Write items one by one (more realistic than bulk write)
        for (byte[] item : serializedItems) {
            out.write(item);
        }
        out.close();
        
        blackhole.consume(baos.toByteArray());
    }

    /**
     * Benchmark compression effectiveness.
     * Measures the compressed size as a percentage of original size.
     * Lower is better (more compression).
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    public long compressionRatio() throws IOException {
        // Calculate uncompressed size
        long uncompressedSize = uncompressedData.length;
        
        // Compress data
        ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompressedData.length);
        OutputStream out = createCompressionStream(baos, compressionType);
        out.write(uncompressedData);
        out.close();
        
        long compressedSize = baos.size();
        
        // Return ratio as percentage (e.g., 45 means compressed to 45% of original)
        return (compressedSize * 100) / uncompressedSize;
    }

    /**
     * Helper method to create compression stream based on type.
     */
    private OutputStream createCompressionStream(OutputStream base, String type) throws IOException {
        return switch (type) {
            case "NONE" -> base;
            case "GZIP_FAST" -> new GZIPOutputStream(base, 1024 * 256) {
                {
                    // Access the protected 'def' field to set compression level
                    def.setLevel(Deflater.BEST_SPEED); // Level 1
                }
            };
            case "GZIP_DEFAULT" -> new GZIPOutputStream(base, 1024 * 256); // Level 6
            case "GZIP_BEST" -> new GZIPOutputStream(base, 1024 * 256) {
                {
                    def.setLevel(Deflater.BEST_COMPRESSION); // Level 9
                }
            };
            default -> throw new IllegalArgumentException("Unknown compression type: " + type);
        };
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

